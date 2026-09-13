#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cfloat>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <functional>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <utility>
#include <vector>

#include <gpu.h>
#include <layer.h>
#include <layer_type.h>
#include <net.h>

namespace {
constexpr const char* kTag = "DigitorWhisperNcnn";
constexpr int kSampleRate = 16000;
constexpr int kMaxChunkSamples = 480000; // 30 seconds, matching the exported Whisper graph.
constexpr int kTokenEndOfText = 50257;
constexpr int kTokenStartOfTranscript = 50258;
constexpr int kTokenLangFirst = 50259;
constexpr int kTokenLangLast = 50357;
constexpr int kTokenTranscribe = 50359;
constexpr int kTokenNoCaptions = 50362;
constexpr int kTokenNoTimestamps = 50363;
constexpr int kMaxDecodedTokens = 448;
constexpr int kBeamSize = 5;
constexpr int kMaxFinishedBeams = 5;
constexpr int kTopK = 5;

// Token order is defined by the multilingual OpenAI Whisper tokenizer and matches Tencent ncnn's
// official examples/whisper.cpp implementation.
constexpr const char* kLanguageCodes[] = {
    "en", "zh", "de", "es", "ru", "ko", "fr", "ja", "pt", "tr", "pl", "ca", "nl", "ar", "sv",
    "it", "id", "hi", "fi", "vi", "he", "uk", "el", "ms", "cs", "ro", "da", "hu", "ta", "no",
    "th", "ur", "hr", "bg", "lt", "la", "mi", "ml", "cy", "sk", "te", "fa", "lv", "bn", "sr",
    "az", "sl", "kn", "et", "mk", "br", "eu", "is", "hy", "ne", "mn", "bs", "kk", "sq", "sw",
    "gl", "mr", "pa", "si", "km", "sn", "yo", "so", "af", "oc", "ka", "be", "tg", "sd", "gu",
    "am", "yi", "lo", "uz", "fo", "ht", "ps", "tk", "nn", "mt", "sa", "lb", "my", "bo", "tl",
    "mg", "as", "tt", "haw", "ln", "ha", "ba", "jw", "su"
};
constexpr int kLanguageCount = sizeof(kLanguageCodes) / sizeof(kLanguageCodes[0]);

std::once_flag gGpuInitOnce;
int gGpuInitResult = -1;
std::mutex gEngineMutex;

std::string JStringToString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    if (chars == nullptr) return {};
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}

void ThrowJava(JNIEnv* env, const char* type, const std::string& message) {
    jclass klass = env->FindClass(type);
    if (klass != nullptr) env->ThrowNew(klass, message.c_str());
}

std::string Trim(std::string text) {
    const auto first = text.find_first_not_of(" \t\r\n");
    if (first == std::string::npos) return {};
    const auto last = text.find_last_not_of(" \t\r\n");
    return text.substr(first, last - first + 1);
}

bool EnsureVulkanRuntime() {
    std::call_once(gGpuInitOnce, [] {
        gGpuInitResult = ncnn::create_gpu_instance();
        if (gGpuInitResult == 0 && ncnn::get_gpu_count() <= 0) gGpuInitResult = -2;
    });
    if (gGpuInitResult != 0 || ncnn::get_gpu_count() <= 0) return false;
    const int gpuIndex = ncnn::get_default_gpu_index();
    if (gpuIndex < 0) return false;
    ncnn::VulkanDevice* device = ncnn::get_gpu_device(gpuIndex);
    return device != nullptr && device->is_valid();
}

class Tokenizer {
public:
    bool load(const std::string& path) {
        std::ifstream input(path);
        if (!input.is_open()) return false;
        reverseVocab.clear();
        std::string line;
        while (std::getline(input, line)) {
            if (!line.empty() && line.back() == '\r') line.pop_back();
            reverseVocab.push_back(line);
        }
        generateByteDecoder();
        return !reverseVocab.empty();
    }

    std::string decodeText(const std::vector<int>& tokens) const {
        std::string encoded;
        for (const int token : tokens) {
            if (token >= 0 && token < kTokenEndOfText && token < static_cast<int>(reverseVocab.size())) {
                encoded += reverseVocab[static_cast<size_t>(token)];
            }
        }
        if (encoded.empty()) return {};

        const std::vector<uint32_t> codepoints = utf8ToCodepoints(encoded);
        std::string bytes;
        bytes.reserve(codepoints.size());
        for (const uint32_t cp : codepoints) {
            if (cp < 512) bytes.push_back(static_cast<char>(byteDecoder[cp]));
        }
        return Trim(bytes);
    }

private:
    std::vector<std::string> reverseVocab;
    uint8_t byteDecoder[512]{};

    void generateByteDecoder() {
        std::fill(byteDecoder, byteDecoder + 512, 0);
        auto printable = [](int b) {
            return (b >= '!' && b <= '~') || (b >= 161 && b <= 172) || (b >= 174 && b <= 255);
        };
        for (int b = 0; b < 256; ++b) {
            if (printable(b)) byteDecoder[b] = static_cast<uint8_t>(b);
        }
        int n = 0;
        for (int b = 0; b < 256; ++b) {
            if (!printable(b)) byteDecoder[256 + n++] = static_cast<uint8_t>(b);
        }
    }

    static std::vector<uint32_t> utf8ToCodepoints(const std::string& text) {
        std::vector<uint32_t> result;
        for (size_t i = 0; i < text.size();) {
            const unsigned char c = static_cast<unsigned char>(text[i]);
            uint32_t cp = 0;
            size_t len = 1;
            if (c < 0x80) {
                cp = c;
            } else if ((c & 0xE0) == 0xC0 && i + 1 < text.size()) {
                cp = ((c & 0x1F) << 6) | (static_cast<unsigned char>(text[i + 1]) & 0x3F);
                len = 2;
            } else if ((c & 0xF0) == 0xE0 && i + 2 < text.size()) {
                cp = ((c & 0x0F) << 12) |
                     ((static_cast<unsigned char>(text[i + 1]) & 0x3F) << 6) |
                     (static_cast<unsigned char>(text[i + 2]) & 0x3F);
                len = 3;
            } else if ((c & 0xF8) == 0xF0 && i + 3 < text.size()) {
                cp = ((c & 0x07) << 18) |
                     ((static_cast<unsigned char>(text[i + 1]) & 0x3F) << 12) |
                     ((static_cast<unsigned char>(text[i + 2]) & 0x3F) << 6) |
                     (static_cast<unsigned char>(text[i + 3]) & 0x3F);
                len = 4;
            } else {
                ++i;
                continue;
            }
            result.push_back(cp);
            i += len;
        }
        return result;
    }
};

struct CaptionSegment {
    int64_t startUs = 0;
    int64_t endUs = 0;
    std::string text;
};

struct BeamResult {
    std::vector<int> ids;
    float score = 0.f;
    std::vector<ncnn::Mat> kvcache;
};

void LogSoftmaxInPlace(ncnn::Mat& logits) {
    ncnn::Option option;
    option.use_packing_layout = false;
    option.use_fp16_storage = false;

    std::unique_ptr<ncnn::Layer> softmax(ncnn::create_layer_cpu("Softmax"));
    if (softmax) {
        ncnn::ParamDict params;
        params.set(0, 0);
        softmax->load_param(params);
        softmax->forward_inplace(logits, option);
    }

    std::unique_ptr<ncnn::Layer> log(ncnn::create_layer_cpu("UnaryOp"));
    if (log) {
        ncnn::ParamDict params;
        params.set(0, 8);
        log->load_param(params);
        log->forward_inplace(logits, option);
    }
}

std::vector<CaptionSegment> SplitTranscriptAcrossDuration(
    const std::string& transcript,
    int64_t chunkOffsetUs,
    int64_t durationUs
) {
    const std::string clean = Trim(transcript);
    if (clean.empty() || durationUs <= 0) return {};

    std::istringstream stream(clean);
    std::vector<std::string> words;
    std::string word;
    while (stream >> word) words.push_back(word);
    if (words.empty()) return {{chunkOffsetUs, chunkOffsetUs + durationUs, clean}};

    constexpr size_t kWordsPerCaption = 8;
    std::vector<CaptionSegment> result;
    size_t wordStart = 0;
    while (wordStart < words.size()) {
        const size_t wordEnd = std::min(words.size(), wordStart + kWordsPerCaption);
        std::string text;
        for (size_t i = wordStart; i < wordEnd; ++i) {
            if (!text.empty()) text.push_back(' ');
            text += words[i];
        }
        const int64_t localStart = durationUs * static_cast<int64_t>(wordStart) / static_cast<int64_t>(words.size());
        int64_t localEnd = durationUs * static_cast<int64_t>(wordEnd) / static_cast<int64_t>(words.size());
        if (localEnd <= localStart) localEnd = std::min(durationUs, localStart + 180000LL);
        if (!text.empty() && localEnd > localStart) {
            result.push_back({chunkOffsetUs + localStart, chunkOffsetUs + localEnd, text});
        }
        wordStart = wordEnd;
    }
    return result;
}

struct WhisperNcnnEngine {
    ncnn::Net fbank;
    ncnn::Net encoder;
    ncnn::Net embedToken;
    ncnn::Net embedPosition;
    ncnn::Net decoder;
    ncnn::Net projOut;
    Tokenizer tokenizer;
    std::vector<int> kvCacheIndexes;
    std::vector<int> outKvCacheIndexes;
    ncnn::VulkanDevice* vkdev = nullptr;
    int gpuIndex = -1;
    std::string gpuName;
    std::string modelDir;

    bool load(const std::string& directory, std::string& error) {
        if (!EnsureVulkanRuntime()) {
            error = "ncnn Vulkan GPU unavailable on this device";
            return false;
        }
        gpuIndex = ncnn::get_default_gpu_index();
        vkdev = ncnn::get_gpu_device(gpuIndex);
        if (vkdev == nullptr || !vkdev->is_valid()) {
            error = "ncnn Vulkan device could not be initialized";
            return false;
        }

        const ncnn::GpuInfo& gpuInfo = ncnn::get_gpu_info(gpuIndex);
        const char* name = gpuInfo.device_name();
        gpuName = (name != nullptr && name[0] != '\0') ? name : "Vulkan GPU";
        modelDir = directory;

        configureGpuNet(fbank);
        configureGpuNet(encoder);
        configureGpuNet(decoder);
        configureGpuNet(projOut);
        embedToken.opt.num_threads = 2;
        embedPosition.opt.num_threads = 2;

        if (!loadNet(fbank, "whisper_base_fbank.ncnn.param", "whisper_base_fbank.ncnn.bin", error)) return false;
        if (!loadNet(encoder, "whisper_base_encoder.ncnn.param", "whisper_base_encoder.ncnn.bin", error)) return false;
        if (!loadNet(embedToken, "whisper_base_embed_token.ncnn.param", "whisper_base_embed_token.ncnn.bin", error)) return false;
        if (!loadNet(embedPosition, "whisper_base_embed_position.ncnn.param", "whisper_base_embed_position.ncnn.bin", error)) return false;
        if (!loadNet(decoder, "whisper_base_decoder.ncnn.param", "whisper_base_decoder.ncnn.bin", error)) return false;
        // The official release has byte-identical proj_out and embed_token weight blobs.
        if (!loadNet(projOut, "whisper_base_proj_out.ncnn.param", "whisper_base_embed_token.ncnn.bin", error)) return false;
        if (!tokenizer.load(path("whisper_vocab.txt"))) {
            error = "Could not load Whisper vocabulary";
            return false;
        }

        kvCacheIndexes.clear();
        outKvCacheIndexes.clear();
        for (const ncnn::Layer* layer : decoder.layers()) {
            if (layer == nullptr || layer->typeindex != ncnn::LayerType::MultiHeadAttention) continue;
            const size_t inputCount = layer->bottoms.size();
            const size_t outputCount = layer->tops.size();
            if (inputCount >= 2 && outputCount == 3) {
                kvCacheIndexes.push_back(layer->bottoms[inputCount - 2]);
                kvCacheIndexes.push_back(layer->bottoms[inputCount - 1]);
                outKvCacheIndexes.push_back(layer->tops[outputCount - 2]);
                outKvCacheIndexes.push_back(layer->tops[outputCount - 1]);
            }
        }
        if (kvCacheIndexes.empty() || kvCacheIndexes.size() != outKvCacheIndexes.size()) {
            error = "Whisper decoder KV-cache graph is incompatible";
            return false;
        }

        __android_log_print(
            ANDROID_LOG_INFO,
            kTag,
            "ncnn Whisper base ready on %s (Vulkan, kv=%zu)",
            gpuName.c_str(),
            kvCacheIndexes.size()
        );
        return true;
    }

    int detectLanguage(const float* samples, int sampleCount, std::string& error) const {
        ncnn::Mat features;
        int status = extractFbank(samples, sampleCount, features);
        if (status != 0) {
            error = "fbank failed during language detection (status=" + std::to_string(status) + ")";
            return -1;
        }
        ncnn::Mat encoded;
        status = runEncoder(features, encoded);
        if (status != 0) {
            error = "encoder failed during language detection (status=" + std::to_string(status) + ")";
            return -1;
        }
        ncnn::Mat logits;
        std::vector<ncnn::Mat> cache;
        status = runDecoderPrefill({kTokenStartOfTranscript}, encoded, logits, cache);
        if (status != 0 || logits.empty() || logits.w <= kTokenLangLast) {
            error = "decoder failed during language detection (status=" + std::to_string(status) + ")";
            return -1;
        }

        int best = kTokenLangFirst;
        float bestScore = logits[kTokenLangFirst];
        for (int token = kTokenLangFirst + 1; token <= kTokenLangLast; ++token) {
            const float score = logits[token];
            if (score > bestScore) {
                bestScore = score;
                best = token;
            }
        }
        return best - kTokenLangFirst;
    }

    bool transcribeChunk(
        const float* samples,
        int sampleCount,
        int languageIndex,
        int64_t chunkOffsetUs,
        std::vector<CaptionSegment>& result,
        std::string& error
    ) const {
        if (languageIndex < 0 || languageIndex >= kLanguageCount || sampleCount <= 0) {
            error = "invalid Whisper chunk or language";
            return false;
        }

        ncnn::Mat features;
        int status = extractFbank(samples, sampleCount, features);
        if (status != 0) {
            error = "fbank failed (status=" + std::to_string(status) + ")";
            return false;
        }
        ncnn::Mat encoded;
        status = runEncoder(features, encoded);
        if (status != 0) {
            error = "encoder failed (status=" + std::to_string(status) + ")";
            return false;
        }

        // Mirror Tencent ncnn examples/whisper.cpp exactly: the fourth prompt token explicitly asks
        // for text without timestamp tokens. This avoids the previous custom greedy/timestamp path
        // accidentally choosing a functional token and returning an empty transcript.
        const std::vector<int> prompt = {
            kTokenStartOfTranscript,
            kTokenLangFirst + languageIndex,
            kTokenTranscribe,
            kTokenNoTimestamps,
        };

        std::vector<BeamResult> finished;
        std::vector<BeamResult> beams(1);
        beams[0].ids = prompt;
        beams[0].score = 0.f;

        for (int step = 0; step < kMaxDecodedTokens && !beams.empty() &&
                           static_cast<int>(finished.size()) < kMaxFinishedBeams; ++step) {
            std::vector<BeamResult> candidates;
            for (const BeamResult& beam : beams) {
                ncnn::Mat logits;
                std::vector<ncnn::Mat> outCache;
                status = step == 0
                    ? runDecoderPrefill(beam.ids, encoded, logits, outCache)
                    : runDecoderStep(beam.ids, encoded, logits, beam.kvcache, outCache);
                if (status != 0 || logits.empty()) {
                    error = "decoder failed at token " + std::to_string(step) +
                            " (status=" + std::to_string(status) + ")";
                    return false;
                }

                LogSoftmaxInPlace(logits);
                const int topk = std::min(kTopK, logits.w);
                std::vector<std::pair<float, int>> ranked(static_cast<size_t>(logits.w));
                for (int token = 0; token < logits.w; ++token) {
                    ranked[static_cast<size_t>(token)] = {logits[token], token};
                }
                std::partial_sort(
                    ranked.begin(), ranked.begin() + topk, ranked.end(),
                    std::greater<std::pair<float, int>>()
                );

                for (int i = 0; i < topk; ++i) {
                    BeamResult candidate;
                    candidate.ids = beam.ids;
                    candidate.ids.push_back(ranked[static_cast<size_t>(i)].second);
                    candidate.score = beam.score + ranked[static_cast<size_t>(i)].first;
                    candidate.kvcache = outCache;
                    candidates.push_back(std::move(candidate));
                }
            }

            std::sort(candidates.begin(), candidates.end(), [](const BeamResult& a, const BeamResult& b) {
                return a.score > b.score;
            });

            beams.clear();
            for (BeamResult& candidate : candidates) {
                if (candidate.ids.back() == kTokenEndOfText) {
                    finished.push_back(std::move(candidate));
                } else if (static_cast<int>(beams.size()) < kBeamSize) {
                    beams.push_back(std::move(candidate));
                }
            }
        }

        // If the token cap is reached without EOT, preserve the best live beam. It is more useful to
        // return the recognized text than to misreport audible speech as "No speech detected".
        if (finished.empty() && !beams.empty()) finished.push_back(beams.front());
        if (finished.empty()) {
            error = "decoder produced no beam";
            return false;
        }

        size_t bestIndex = 0;
        float bestAverage = -FLT_MAX;
        for (size_t i = 0; i < finished.size(); ++i) {
            const BeamResult& candidate = finished[i];
            const float average = candidate.ids.empty()
                ? -FLT_MAX
                : candidate.score / static_cast<float>(candidate.ids.size());
            if (average > bestAverage) {
                bestAverage = average;
                bestIndex = i;
            }
        }

        const std::string transcript = tokenizer.decodeText(finished[bestIndex].ids);
        const int64_t durationUs = static_cast<int64_t>(sampleCount) * 1000000LL / kSampleRate;
        const std::vector<CaptionSegment> split =
            SplitTranscriptAcrossDuration(transcript, chunkOffsetUs, durationUs);
        result.insert(result.end(), split.begin(), split.end());

        __android_log_print(
            ANDROID_LOG_INFO,
            kTag,
            "GPU chunk done: samples=%d lang=%s textBytes=%zu captions=%zu bestAvg=%.4f nocaptionsToken=%d",
            sampleCount,
            kLanguageCodes[languageIndex],
            transcript.size(),
            split.size(),
            bestAverage,
            kTokenNoCaptions
        );
        return true;
    }

private:
    std::string path(const char* name) const {
        return modelDir + "/" + name;
    }

    void configureGpuNet(ncnn::Net& net) {
        net.opt.use_vulkan_compute = true;
        net.opt.num_threads = 2;
        // Match Tencent's official Whisper ncnn reference: stable FP32 storage/arithmetic.
        net.opt.use_fp16_packed = false;
        net.opt.use_fp16_storage = false;
        net.opt.use_fp16_arithmetic = false;
        net.set_vulkan_device(vkdev);
    }

    bool loadNet(ncnn::Net& net, const char* paramName, const char* binName, std::string& error) {
        const std::string param = path(paramName);
        const std::string bin = path(binName);
        const int paramStatus = net.load_param(param.c_str());
        if (paramStatus != 0) {
            error = std::string("Could not load ") + paramName + " (status=" + std::to_string(paramStatus) + ")";
            return false;
        }
        const int modelStatus = net.load_model(bin.c_str());
        if (modelStatus != 0) {
            error = std::string("Could not load ") + binName + " (status=" + std::to_string(modelStatus) + ")";
            return false;
        }
        return true;
    }

    int extractFbank(const float* samples, int sampleCount, ncnn::Mat& features) const {
        ncnn::Mat waveform(kMaxChunkSamples);
        waveform.fill(0.f);
        const int copyCount = std::min(sampleCount, kMaxChunkSamples);
        float* dst = waveform;
        for (int i = 0; i < copyCount; ++i) dst[i] = std::clamp(samples[i], -1.f, 1.f);

        ncnn::Extractor ex = fbank.create_extractor();
        int status = ex.input("in0", waveform);
        if (status != 0) return status;
        status = ex.extract("out0", features);
        if (status != 0 || features.empty() || features.w <= 1) return status != 0 ? status : -1;

        // Exact Tencent ncnn Whisper reference behavior: fbank produces 3001 frames; encoder expects 3000.
        ncnn::Mat trimmed(features.w - 1, features.h);
        for (int row = 0; row < features.h; ++row) {
            std::memcpy(trimmed.row(row), features.row(row), static_cast<size_t>(features.w - 1) * sizeof(float));
        }
        features = trimmed;
        return 0;
    }

    int runEncoder(const ncnn::Mat& features, ncnn::Mat& states) const {
        ncnn::Extractor ex = encoder.create_extractor();
        int status = ex.input("in0", features);
        if (status != 0) return status;
        return ex.extract("out0", states);
    }

    int runDecoderPrefill(
        const std::vector<int>& tokens,
        const ncnn::Mat& encoderStates,
        ncnn::Mat& lastLogits,
        std::vector<ncnn::Mat>& outCache
    ) const {
        const int seqLen = static_cast<int>(tokens.size());
        ncnn::Mat inputTokens(seqLen);
        std::memcpy(static_cast<int*>(inputTokens), tokens.data(), tokens.size() * sizeof(int));

        ncnn::Mat tokenEmbeds;
        {
            ncnn::Extractor ex = embedToken.create_extractor();
            int status = ex.input("in0", inputTokens);
            if (status != 0) return status;
            status = ex.extract("out0", tokenEmbeds);
            if (status != 0) return status;
        }

        ncnn::Mat positions(seqLen);
        int* positionData = positions;
        for (int i = 0; i < seqLen; ++i) positionData[i] = i;
        ncnn::Mat positionEmbeds;
        {
            ncnn::Extractor ex = embedPosition.create_extractor();
            int status = ex.input("in0", positions);
            if (status != 0) return status;
            status = ex.extract("out0", positionEmbeds);
            if (status != 0) return status;
        }

        if (tokenEmbeds.total() != positionEmbeds.total()) return -1;
        ncnn::Mat inputEmbeds;
        inputEmbeds.create_like(tokenEmbeds);
        float* inputPtr = inputEmbeds;
        const float* tokenPtr = tokenEmbeds;
        const float* positionPtr = positionEmbeds;
        for (size_t i = 0; i < inputEmbeds.total(); ++i) inputPtr[i] = tokenPtr[i] + positionPtr[i];

        ncnn::Mat attentionMask(seqLen, seqLen);
        attentionMask.fill(0.f);
        for (int i = 0; i < seqLen; ++i) {
            float* row = attentionMask.row(i);
            for (int j = i + 1; j < seqLen; ++j) row[j] = -INFINITY;
        }

        ncnn::Mat outputStates;
        {
            ncnn::Extractor ex = decoder.create_extractor();
            int status = ex.input("in0", inputEmbeds);
            if (status != 0) return status;
            status = ex.input("in1", encoderStates);
            if (status != 0) return status;
            status = ex.input("in2", attentionMask);
            if (status != 0) return status;

            outCache.resize(outKvCacheIndexes.size());
            for (size_t i = 0; i < outKvCacheIndexes.size(); ++i) {
                status = ex.extract(outKvCacheIndexes[i], outCache[i], 1);
                if (status != 0) return status;
            }
            status = ex.extract("out0", outputStates);
            if (status != 0) return status;
        }

        ncnn::Mat lastState = outputStates.row_range(seqLen - 1, 1).clone();
        ncnn::Extractor projection = projOut.create_extractor();
        int status = projection.input("in0", lastState);
        if (status != 0) return status;
        status = projection.extract("out0", lastLogits);
        if (status != 0) return status;
        lastLogits = lastLogits.reshape(lastLogits.w);
        return 0;
    }

    int runDecoderStep(
        const std::vector<int>& tokens,
        const ncnn::Mat& encoderStates,
        ncnn::Mat& lastLogits,
        const std::vector<ncnn::Mat>& cache,
        std::vector<ncnn::Mat>& outCache
    ) const {
        if (cache.size() != kvCacheIndexes.size()) return -1;
        ncnn::Mat inputTokens(1);
        static_cast<int*>(inputTokens)[0] = tokens.back();

        ncnn::Mat tokenEmbeds;
        {
            ncnn::Extractor ex = embedToken.create_extractor();
            int status = ex.input("in0", inputTokens);
            if (status != 0) return status;
            status = ex.extract("out0", tokenEmbeds);
            if (status != 0) return status;
        }

        ncnn::Mat positions(1);
        static_cast<int*>(positions)[0] = static_cast<int>(tokens.size()) - 1;
        ncnn::Mat positionEmbeds;
        {
            ncnn::Extractor ex = embedPosition.create_extractor();
            int status = ex.input("in0", positions);
            if (status != 0) return status;
            status = ex.extract("out0", positionEmbeds);
            if (status != 0) return status;
        }

        ncnn::Mat inputEmbeds;
        inputEmbeds.create_like(tokenEmbeds);
        float* inputPtr = inputEmbeds;
        const float* tokenPtr = tokenEmbeds;
        const float* positionPtr = positionEmbeds;
        for (size_t i = 0; i < inputEmbeds.total(); ++i) inputPtr[i] = tokenPtr[i] + positionPtr[i];

        // Exact Tencent reference behavior: one cached decoder token needs only a 1x1 zero mask.
        ncnn::Mat attentionMask(1, 1);
        attentionMask.fill(0.f);
        ncnn::Mat outputStates;
        {
            ncnn::Extractor ex = decoder.create_extractor();
            int status = ex.input("in0", inputEmbeds);
            if (status != 0) return status;
            status = ex.input("in1", encoderStates);
            if (status != 0) return status;
            status = ex.input("in2", attentionMask);
            if (status != 0) return status;
            for (size_t i = 0; i < kvCacheIndexes.size(); ++i) {
                status = ex.input(kvCacheIndexes[i], cache[i]);
                if (status != 0) return status;
            }
            outCache.resize(outKvCacheIndexes.size());
            for (size_t i = 0; i < outKvCacheIndexes.size(); ++i) {
                status = ex.extract(outKvCacheIndexes[i], outCache[i], 1);
                if (status != 0) return status;
            }
            status = ex.extract("out0", outputStates);
            if (status != 0) return status;
        }

        ncnn::Mat lastState = outputStates.row_range(0, 1).clone();
        ncnn::Extractor projection = projOut.create_extractor();
        int status = projection.input("in0", lastState);
        if (status != 0) return status;
        status = projection.extract("out0", lastLogits);
        if (status != 0) return status;
        lastLogits = lastLogits.reshape(lastLogits.w);
        return 0;
    }
};

std::unique_ptr<WhisperNcnnEngine> gEngine;

int FindLanguageIndex(const std::string& code) {
    for (int i = 0; i < kLanguageCount; ++i) {
        if (code == kLanguageCodes[i]) return i;
    }
    return -1;
}

std::string BackendLabel() {
    if (!gEngine) return "ncnn Vulkan GPU";
    return std::string("ncnn Vulkan GPU · ") + gEngine->gpuName;
}

bool EnsureEngine(const std::string& modelDir, std::string& error) {
    if (gEngine && gEngine->modelDir == modelDir) return true;
    auto engine = std::make_unique<WhisperNcnnEngine>();
    if (!engine->load(modelDir, error)) return false;
    gEngine = std::move(engine);
    return true;
}

void MeasurePcm(const std::vector<float>& samples, float& peak, double& rms) {
    peak = 0.f;
    double squares = 0.0;
    if (samples.empty()) {
        rms = 0.0;
        return;
    }
    for (const float sample : samples) {
        const float value = std::fabs(sample);
        peak = std::max(peak, value);
        squares += static_cast<double>(sample) * static_cast<double>(sample);
    }
    rms = std::sqrt(squares / static_cast<double>(samples.size()));
}

} // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_tajuli_digitorandroid_editor_processing_WhisperLanguageNativeV82_supportedLanguages(
    JNIEnv* env,
    jobject
) {
    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) return nullptr;
    jobjectArray result = env->NewObjectArray(kLanguageCount, stringClass, nullptr);
    if (result == nullptr) return nullptr;
    for (int i = 0; i < kLanguageCount; ++i) {
        const std::string encoded = std::string(kLanguageCodes[i]) + "\t" + kLanguageCodes[i];
        jstring value = env->NewStringUTF(encoded.c_str());
        env->SetObjectArrayElement(result, i, value);
        env->DeleteLocalRef(value);
    }
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tajuli_digitorandroid_editor_processing_WhisperNativeV80_prepareBackend(
    JNIEnv* env,
    jobject,
    jstring modelPathValue,
    jboolean
) {
    const std::string modelDir = JStringToString(env, modelPathValue);
    if (modelDir.empty()) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Whisper ncnn model directory is required");
        return nullptr;
    }
    std::lock_guard<std::mutex> guard(gEngineMutex);
    std::string error;
    if (!EnsureEngine(modelDir, error)) {
        ThrowJava(env, "java/lang/IllegalStateException", "GPU_BACKENDS_UNAVAILABLE: " + error);
        return nullptr;
    }
    const std::string label = BackendLabel();
    return env->NewStringUTF(label.c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_tajuli_digitorandroid_editor_processing_WhisperNativeV80_activeBackend(
    JNIEnv* env,
    jobject
) {
    std::lock_guard<std::mutex> guard(gEngineMutex);
    const std::string label = BackendLabel();
    return env->NewStringUTF(label.c_str());
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_tajuli_digitorandroid_editor_processing_WhisperNativeV80_transcribe(
    JNIEnv* env,
    jobject,
    jstring modelPathValue,
    jfloatArray samplesValue,
    jstring languageValue,
    jboolean
) {
    if (modelPathValue == nullptr || samplesValue == nullptr) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Whisper model directory and PCM samples are required");
        return nullptr;
    }

    const std::string modelDir = JStringToString(env, modelPathValue);
    const std::string requestedLanguage = JStringToString(env, languageValue);
    const jsize sampleCount = env->GetArrayLength(samplesValue);
    std::vector<float> samples(static_cast<size_t>(std::max<jsize>(0, sampleCount)));
    if (sampleCount > 0) env->GetFloatArrayRegion(samplesValue, 0, sampleCount, samples.data());
    if (env->ExceptionCheck()) return nullptr;

    float peak = 0.f;
    double rms = 0.0;
    MeasurePcm(samples, peak, rms);
    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "PCM received: samples=%d duration=%.2fs peak=%.6f rms=%.6f",
        static_cast<int>(sampleCount),
        static_cast<double>(sampleCount) / kSampleRate,
        peak,
        rms
    );

    std::lock_guard<std::mutex> guard(gEngineMutex);
    std::string error;
    if (!EnsureEngine(modelDir, error)) {
        ThrowJava(env, "java/lang/IllegalStateException", "GPU_BACKENDS_UNAVAILABLE: " + error);
        return nullptr;
    }

    int languageIndex = requestedLanguage.empty() || requestedLanguage == "auto"
        ? -1
        : FindLanguageIndex(requestedLanguage);
    if (languageIndex < 0 && !(requestedLanguage.empty() || requestedLanguage == "auto")) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Unsupported Whisper language: " + requestedLanguage);
        return nullptr;
    }

    std::vector<CaptionSegment> segments;
    int offset = 0;
    while (offset < sampleCount) {
        const int count = std::min(kMaxChunkSamples, static_cast<int>(sampleCount) - offset);
        const float* chunk = samples.data() + offset;

        // V88 intentionally does not pre-reject low-level PCM. The previous hand-written energy gate
        // could classify a real but quiet voice as silence before Whisper ever saw it. Whisper's own
        // decoder now decides whether the chunk contains transcribable speech.
        if (languageIndex < 0) {
            languageIndex = gEngine->detectLanguage(chunk, count, error);
            if (languageIndex < 0 || languageIndex >= kLanguageCount) {
                ThrowJava(env, "java/lang/IllegalStateException", "ncnn Whisper language detection failed · " + error);
                return nullptr;
            }
            __android_log_print(
                ANDROID_LOG_INFO,
                kTag,
                "Auto Detect resolved language=%s",
                kLanguageCodes[languageIndex]
            );
        }

        const int64_t offsetUs = static_cast<int64_t>(offset) * 1000000LL / kSampleRate;
        if (!gEngine->transcribeChunk(chunk, count, languageIndex, offsetUs, segments, error)) {
            ThrowJava(env, "java/lang/IllegalStateException", "ncnn Whisper GPU inference failed · " + error);
            return nullptr;
        }
        offset += count;
    }

    if (segments.empty()) {
        char detail[256];
        std::snprintf(
            detail,
            sizeof(detail),
            "ncnn Whisper GPU produced no transcript · PCM %.2fs · peak %.5f · RMS %.5f · %s",
            static_cast<double>(sampleCount) / kSampleRate,
            peak,
            rms,
            BackendLabel().c_str()
        );
        ThrowJava(env, "java/lang/IllegalStateException", detail);
        return nullptr;
    }

    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) return nullptr;
    jobjectArray result = env->NewObjectArray(static_cast<jsize>(segments.size()), stringClass, nullptr);
    if (result == nullptr) return nullptr;
    for (size_t i = 0; i < segments.size(); ++i) {
        const CaptionSegment& segment = segments[i];
        const std::string encoded = std::to_string(segment.startUs) + "\t" +
            std::to_string(segment.endUs) + "\t" + segment.text;
        jstring value = env->NewStringUTF(encoded.c_str());
        env->SetObjectArrayElement(result, static_cast<jsize>(i), value);
        env->DeleteLocalRef(value);
    }

    __android_log_print(
        ANDROID_LOG_INFO,
        kTag,
        "GPU transcription complete: backend=%s samples=%d segments=%zu peak=%.6f rms=%.6f",
        BackendLabel().c_str(),
        static_cast<int>(sampleCount),
        segments.size(),
        peak,
        rms
    );
    return result;
}
