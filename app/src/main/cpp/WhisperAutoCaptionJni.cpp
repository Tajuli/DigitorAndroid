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
constexpr int kMaxDecodedTokens = 192;
constexpr int kKvCacheUnavailable = -7001;

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
            // Whisper special/control tokens start at end-of-text. Only decode actual text tokens.
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
        if (localEnd <= localStart) {
            localEnd = std::min<int64_t>(durationUs, localStart + static_cast<int64_t>(180000));
        }
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

        // The fbank graph is lightweight. Keep it CPU-side because the target Mali driver already
        // demonstrated an fbank Vulkan failure; Whisper inference itself remains GPU-first.
        fbank.opt.use_vulkan_compute = false;
        fbank.opt.num_threads = 2;
        fbank.opt.use_fp16_packed = false;
        fbank.opt.use_fp16_storage = false;
        fbank.opt.use_fp16_arithmetic = false;

        configureGpuNet(encoder, false);
        configureGpuNet(decoder, true);
        configureGpuNet(projOut, false);
        embedToken.opt.num_threads = 2;
        embedPosition.opt.num_threads = 2;

        if (!loadNet(fbank, "whisper_tiny_fbank.ncnn.param", "whisper_tiny_fbank.ncnn.bin", error)) return false;
        if (!loadNet(encoder, "whisper_tiny_encoder.ncnn.param", "whisper_tiny_encoder.ncnn.bin", error)) return false;
        if (!loadNet(embedToken, "whisper_tiny_embed_token.ncnn.param", "whisper_tiny_embed_token.ncnn.bin", error)) return false;
        if (!loadNet(embedPosition, "whisper_tiny_embed_position.ncnn.param", "whisper_tiny_embed_position.ncnn.bin", error)) return false;
        if (!loadNet(decoder, "whisper_tiny_decoder.ncnn.param", "whisper_tiny_decoder.ncnn.bin", error)) return false;
        // Official tiny proj_out and embed_token weight blobs are byte-identical.
        if (!loadNet(projOut, "whisper_tiny_proj_out.ncnn.param", "whisper_tiny_embed_token.ncnn.bin", error)) return false;
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
            "ncnn Whisper tiny ready on %s (Vulkan, kv=%zu, safeDecoder=1)",
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

        // Language detection never needs K/V cache. Avoid the old ncnn Vulkan cache-output path
        // completely so Auto Detect can run on mobile drivers where cache extraction is broken.
        ncnn::Mat logits;
        std::string stage;
        status = runDecoderFullSequence({kTokenStartOfTranscript}, encoded, logits, stage);
        if (status != 0 || logits.empty() || logits.w <= kTokenLangLast) {
            error = stage.empty()
                ? "decoder failed during language detection (status=" + std::to_string(status) + ")"
                : stage;
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

        std::vector<int> ids = {
            kTokenStartOfTranscript,
            kTokenLangFirst + languageIndex,
            kTokenTranscribe,
            kTokenNoTimestamps,
        };
        std::vector<ncnn::Mat> cache;
        bool useKvCache = true;
        bool cacheFallbackLogged = false;
        float score = 0.f;

        for (int step = 0; step < kMaxDecodedTokens; ++step) {
            ncnn::Mat logits;
            std::vector<ncnn::Mat> nextCache;
            std::string stage;

            if (step == 0) {
                status = runDecoderPrefill(ids, encoded, logits, nextCache, stage);
                if (status == kKvCacheUnavailable && !logits.empty()) {
                    // Decoder output itself succeeded. Only old Vulkan K/V cache materialization
                    // failed, so keep this GPU result and continue with full-sequence GPU decoding.
                    useKvCache = false;
                    cacheFallbackLogged = true;
                    __android_log_print(
                        ANDROID_LOG_WARN,
                        kTag,
                        "Vulkan KV cache unavailable on %s; using GPU full-sequence decoder (%s)",
                        gpuName.c_str(),
                        stage.c_str()
                    );
                    status = 0;
                } else if (status != 0) {
                    // If the cached prefill path itself fails, retry the same token sequence without
                    // touching K/V cache outputs. This remains ncnn Vulkan GPU inference.
                    const std::string cachedStage = stage;
                    stage.clear();
                    status = runDecoderFullSequence(ids, encoded, logits, stage);
                    if (status == 0 && !logits.empty()) {
                        useKvCache = false;
                        cacheFallbackLogged = true;
                        __android_log_print(
                            ANDROID_LOG_WARN,
                            kTag,
                            "Cached Vulkan prefill failed on %s (%s); GPU no-cache retry succeeded",
                            gpuName.c_str(),
                            cachedStage.c_str()
                        );
                    }
                }
            } else if (useKvCache) {
                status = runDecoderStep(ids, encoded, logits, cache, nextCache, stage);
                if (status == kKvCacheUnavailable && !logits.empty()) {
                    useKvCache = false;
                    cacheFallbackLogged = true;
                    __android_log_print(
                        ANDROID_LOG_WARN,
                        kTag,
                        "Vulkan KV cache update failed at token %d on %s; continuing GPU no-cache (%s)",
                        step,
                        gpuName.c_str(),
                        stage.c_str()
                    );
                    status = 0;
                } else if (status != 0) {
                    const std::string cachedStage = stage;
                    stage.clear();
                    status = runDecoderFullSequence(ids, encoded, logits, stage);
                    if (status == 0 && !logits.empty()) {
                        useKvCache = false;
                        cacheFallbackLogged = true;
                        __android_log_print(
                            ANDROID_LOG_WARN,
                            kTag,
                            "Cached Vulkan decode failed at token %d on %s (%s); GPU no-cache retry succeeded",
                            step,
                            gpuName.c_str(),
                            cachedStage.c_str()
                        );
                    }
                }
            } else {
                status = runDecoderFullSequence(ids, encoded, logits, stage);
            }

            if (status != 0 || logits.empty()) {
                error = "decoder failed at token " + std::to_string(step) +
                    " · " + (stage.empty() ? ("status=" + std::to_string(status)) : stage);
                return false;
            }

            if (useKvCache) cache = std::move(nextCache);
            else cache.clear();

            LogSoftmaxInPlace(logits);
            int bestToken = 0;
            float bestValue = -FLT_MAX;
            for (int token = 0; token < logits.w; ++token) {
                const float value = logits[token];
                if (value > bestValue) {
                    bestValue = value;
                    bestToken = token;
                }
            }
            score += bestValue;
            ids.push_back(bestToken);
            if (bestToken == kTokenEndOfText) break;
        }

        const std::string transcript = tokenizer.decodeText(ids);
        const int64_t durationUs = static_cast<int64_t>(sampleCount) * 1000000LL / kSampleRate;
        const std::vector<CaptionSegment> split =
            SplitTranscriptAcrossDuration(transcript, chunkOffsetUs, durationUs);
        result.insert(result.end(), split.begin(), split.end());

        __android_log_print(
            ANDROID_LOG_INFO,
            kTag,
            "GPU chunk done: samples=%d lang=%s textBytes=%zu captions=%zu score=%.4f mode=%s fallback=%d nocaptionsToken=%d",
            sampleCount,
            kLanguageCodes[languageIndex],
            transcript.size(),
            split.size(),
            score,
            useKvCache ? "vulkan-kvcache" : "vulkan-full-sequence",
            cacheFallbackLogged ? 1 : 0,
            kTokenNoCaptions
        );
        return true;
    }

private:
    std::string path(const char* name) const {
        return modelDir + "/" + name;
    }

    void configureGpuNet(ncnn::Net& net, bool decoderSafeMode) {
        net.opt.use_vulkan_compute = true;
        net.opt.num_threads = 2;
        // Match Tencent's Whisper reference: stable FP32 storage/arithmetic.
        net.opt.use_fp16_packed = false;
        net.opt.use_fp16_storage = false;
        net.opt.use_fp16_arithmetic = false;
        if (decoderSafeMode) {
            // Decoder attention is the only stage that failed on the Mali-G57. Avoid optional Vulkan
            // optimization paths and keep all decoder blobs alive while multiple graph outputs are
            // inspected. Compute still runs on Vulkan; this is not a CPU transcription fallback.
            net.opt.use_subgroup_ops = false;
            net.opt.use_shader_local_memory = false;
            net.opt.use_cooperative_matrix = false;
            net.opt.lightmode = false;
        }
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

        // ncnn Whisper fbank produces 3001 frames; the encoder expects 3000.
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

    int buildDecoderInputs(
        const std::vector<int>& tokens,
        ncnn::Mat& inputEmbeds,
        ncnn::Mat& attentionMask,
        std::string& stage
    ) const {
        const int seqLen = static_cast<int>(tokens.size());
        if (seqLen <= 0) {
            stage = "decoder received empty token sequence";
            return -1;
        }

        ncnn::Mat inputTokens(seqLen);
        std::memcpy(static_cast<int*>(inputTokens), tokens.data(), tokens.size() * sizeof(int));

        ncnn::Mat tokenEmbeds;
        {
            ncnn::Extractor ex = embedToken.create_extractor();
            int status = ex.input("in0", inputTokens);
            if (status != 0) {
                stage = "token embedding input status=" + std::to_string(status);
                return status;
            }
            status = ex.extract("out0", tokenEmbeds);
            if (status != 0 || tokenEmbeds.empty()) {
                stage = "token embedding output status=" + std::to_string(status);
                return status != 0 ? status : -1;
            }
        }

        ncnn::Mat positions(seqLen);
        int* positionData = positions;
        for (int i = 0; i < seqLen; ++i) positionData[i] = i;
        ncnn::Mat positionEmbeds;
        {
            ncnn::Extractor ex = embedPosition.create_extractor();
            int status = ex.input("in0", positions);
            if (status != 0) {
                stage = "position embedding input status=" + std::to_string(status);
                return status;
            }
            status = ex.extract("out0", positionEmbeds);
            if (status != 0 || positionEmbeds.empty()) {
                stage = "position embedding output status=" + std::to_string(status);
                return status != 0 ? status : -1;
            }
        }

        if (tokenEmbeds.total() != positionEmbeds.total()) {
            stage = "embedding shape mismatch";
            return -1;
        }
        inputEmbeds.create_like(tokenEmbeds);
        if (inputEmbeds.empty()) {
            stage = "embedding allocation failed";
            return -100;
        }
        float* inputPtr = inputEmbeds;
        const float* tokenPtr = tokenEmbeds;
        const float* positionPtr = positionEmbeds;
        for (size_t i = 0; i < inputEmbeds.total(); ++i) inputPtr[i] = tokenPtr[i] + positionPtr[i];

        attentionMask.create(seqLen, seqLen);
        if (attentionMask.empty()) {
            stage = "attention-mask allocation failed";
            return -100;
        }
        attentionMask.fill(0.f);
        for (int i = 0; i < seqLen; ++i) {
            float* row = attentionMask.row(i);
            for (int j = i + 1; j < seqLen; ++j) row[j] = -INFINITY;
        }
        return 0;
    }

    int projectLastState(
        const ncnn::Mat& outputStates,
        int row,
        ncnn::Mat& lastLogits,
        std::string& stage
    ) const {
        if (outputStates.empty() || row < 0 || row >= outputStates.h) {
            stage = "decoder output shape invalid";
            return -1;
        }
        ncnn::Mat lastState = outputStates.row_range(row, 1).clone();
        if (lastState.empty()) {
            stage = "decoder last-state copy failed";
            return -100;
        }
        ncnn::Extractor projection = projOut.create_extractor();
        int status = projection.input("in0", lastState);
        if (status != 0) {
            stage = "projection input status=" + std::to_string(status);
            return status;
        }
        status = projection.extract("out0", lastLogits);
        if (status != 0 || lastLogits.empty()) {
            stage = "projection output status=" + std::to_string(status);
            return status != 0 ? status : -1;
        }
        lastLogits = lastLogits.reshape(lastLogits.w);
        if (lastLogits.empty()) {
            stage = "projection reshape failed";
            return -1;
        }
        return 0;
    }

    // GPU-only compatibility path: re-run the full current token sequence and extract only the
    // decoder text output. It deliberately never materializes K/V cache outputs, bypassing the
    // problematic old ncnn Vulkan cache path while keeping decoder inference on the Vulkan GPU.
    int runDecoderFullSequence(
        const std::vector<int>& tokens,
        const ncnn::Mat& encoderStates,
        ncnn::Mat& lastLogits,
        std::string& stage
    ) const {
        ncnn::Mat inputEmbeds;
        ncnn::Mat attentionMask;
        int status = buildDecoderInputs(tokens, inputEmbeds, attentionMask, stage);
        if (status != 0) return status;

        ncnn::Mat outputStates;
        ncnn::Extractor ex = decoder.create_extractor();
        status = ex.input("in0", inputEmbeds);
        if (status != 0) {
            stage = "decoder input0 status=" + std::to_string(status);
            return status;
        }
        status = ex.input("in1", encoderStates);
        if (status != 0) {
            stage = "decoder encoder-state input status=" + std::to_string(status);
            return status;
        }
        status = ex.input("in2", attentionMask);
        if (status != 0) {
            stage = "decoder mask input status=" + std::to_string(status);
            return status;
        }
        status = ex.extract("out0", outputStates);
        if (status != 0 || outputStates.empty()) {
            stage = "decoder out0 status=" + std::to_string(status) + " (GPU full-sequence)";
            return status != 0 ? status : -1;
        }
        return projectLastState(outputStates, static_cast<int>(tokens.size()) - 1, lastLogits, stage);
    }

    int runDecoderPrefill(
        const std::vector<int>& tokens,
        const ncnn::Mat& encoderStates,
        ncnn::Mat& lastLogits,
        std::vector<ncnn::Mat>& outCache,
        std::string& stage
    ) const {
        ncnn::Mat inputEmbeds;
        ncnn::Mat attentionMask;
        int status = buildDecoderInputs(tokens, inputEmbeds, attentionMask, stage);
        if (status != 0) return status;

        ncnn::Mat outputStates;
        ncnn::Extractor ex = decoder.create_extractor();
        status = ex.input("in0", inputEmbeds);
        if (status != 0) {
            stage = "decoder prefill input0 status=" + std::to_string(status);
            return status;
        }
        status = ex.input("in1", encoderStates);
        if (status != 0) {
            stage = "decoder prefill encoder-state input status=" + std::to_string(status);
            return status;
        }
        status = ex.input("in2", attentionMask);
        if (status != 0) {
            stage = "decoder prefill mask input status=" + std::to_string(status);
            return status;
        }

        // Extract text output first. On the May-2026 ncnn runtime the phone failure occurs while
        // materializing MHA cache outputs; getting out0 first lets us keep a valid GPU decode result.
        status = ex.extract("out0", outputStates);
        if (status != 0 || outputStates.empty()) {
            stage = "decoder prefill out0 status=" + std::to_string(status);
            return status != 0 ? status : -1;
        }
        status = projectLastState(outputStates, static_cast<int>(tokens.size()) - 1, lastLogits, stage);
        if (status != 0) return status;

        outCache.resize(outKvCacheIndexes.size());
        for (size_t i = 0; i < outKvCacheIndexes.size(); ++i) {
            status = ex.extract(outKvCacheIndexes[i], outCache[i], 1);
            if (status != 0 || outCache[i].empty()) {
                stage = "Vulkan KV-cache prefill output " + std::to_string(i) +
                    " status=" + std::to_string(status);
                outCache.clear();
                return kKvCacheUnavailable;
            }
        }
        return 0;
    }

    int runDecoderStep(
        const std::vector<int>& tokens,
        const ncnn::Mat& encoderStates,
        ncnn::Mat& lastLogits,
        const std::vector<ncnn::Mat>& cache,
        std::vector<ncnn::Mat>& outCache,
        std::string& stage
    ) const {
        if (cache.size() != kvCacheIndexes.size()) {
            stage = "KV-cache input count mismatch";
            return kKvCacheUnavailable;
        }

        ncnn::Mat inputTokens(1);
        static_cast<int*>(inputTokens)[0] = tokens.back();
        ncnn::Mat tokenEmbeds;
        {
            ncnn::Extractor ex = embedToken.create_extractor();
            int status = ex.input("in0", inputTokens);
            if (status != 0) {
                stage = "step token embedding input status=" + std::to_string(status);
                return status;
            }
            status = ex.extract("out0", tokenEmbeds);
            if (status != 0 || tokenEmbeds.empty()) {
                stage = "step token embedding output status=" + std::to_string(status);
                return status != 0 ? status : -1;
            }
        }

        ncnn::Mat positions(1);
        static_cast<int*>(positions)[0] = static_cast<int>(tokens.size()) - 1;
        ncnn::Mat positionEmbeds;
        {
            ncnn::Extractor ex = embedPosition.create_extractor();
            int status = ex.input("in0", positions);
            if (status != 0) {
                stage = "step position embedding input status=" + std::to_string(status);
                return status;
            }
            status = ex.extract("out0", positionEmbeds);
            if (status != 0 || positionEmbeds.empty()) {
                stage = "step position embedding output status=" + std::to_string(status);
                return status != 0 ? status : -1;
            }
        }

        if (tokenEmbeds.total() != positionEmbeds.total()) {
            stage = "step embedding shape mismatch";
            return -1;
        }
        ncnn::Mat inputEmbeds;
        inputEmbeds.create_like(tokenEmbeds);
        if (inputEmbeds.empty()) {
            stage = "step embedding allocation failed";
            return -100;
        }
        float* inputPtr = inputEmbeds;
        const float* tokenPtr = tokenEmbeds;
        const float* positionPtr = positionEmbeds;
        for (size_t i = 0; i < inputEmbeds.total(); ++i) inputPtr[i] = tokenPtr[i] + positionPtr[i];

        ncnn::Mat attentionMask(1, 1);
        attentionMask.fill(0.f);
        ncnn::Mat outputStates;
        ncnn::Extractor ex = decoder.create_extractor();
        int status = ex.input("in0", inputEmbeds);
        if (status != 0) {
            stage = "decoder step input0 status=" + std::to_string(status);
            return status;
        }
        status = ex.input("in1", encoderStates);
        if (status != 0) {
            stage = "decoder step encoder-state input status=" + std::to_string(status);
            return status;
        }
        status = ex.input("in2", attentionMask);
        if (status != 0) {
            stage = "decoder step mask input status=" + std::to_string(status);
            return status;
        }
        for (size_t i = 0; i < kvCacheIndexes.size(); ++i) {
            status = ex.input(kvCacheIndexes[i], cache[i]);
            if (status != 0) {
                stage = "decoder KV-cache input " + std::to_string(i) +
                    " status=" + std::to_string(status);
                return kKvCacheUnavailable;
            }
        }

        status = ex.extract("out0", outputStates);
        if (status != 0 || outputStates.empty()) {
            stage = "decoder cached out0 status=" + std::to_string(status);
            return status != 0 ? status : -1;
        }
        status = projectLastState(outputStates, 0, lastLogits, stage);
        if (status != 0) return status;

        outCache.resize(outKvCacheIndexes.size());
        for (size_t i = 0; i < outKvCacheIndexes.size(); ++i) {
            status = ex.extract(outKvCacheIndexes[i], outCache[i], 1);
            if (status != 0 || outCache[i].empty()) {
                stage = "Vulkan KV-cache step output " + std::to_string(i) +
                    " status=" + std::to_string(status);
                outCache.clear();
                return kKvCacheUnavailable;
            }
        }
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
