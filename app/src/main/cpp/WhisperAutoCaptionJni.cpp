#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <memory>
#include <mutex>
#include <string>
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
constexpr int kTokenTimestampFirst = 50364;
constexpr int kTokenTimestampLast = 51864;
constexpr int kMaxDecodedTokens = 448;

// Token order is defined by OpenAI Whisper and mirrored by Tencent ncnn's official whisper example.
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
        for (int token : tokens) {
            if (token >= 0 && token < kTokenEndOfText && token < static_cast<int>(reverseVocab.size())) {
                encoded += reverseVocab[static_cast<size_t>(token)];
            }
        }
        if (encoded.empty()) return {};

        const std::vector<uint32_t> codepoints = utf8ToCodepoints(encoded);
        std::string bytes;
        bytes.reserve(codepoints.size());
        for (uint32_t cp : codepoints) {
            if (cp < 512) bytes.push_back(static_cast<char>(byteDecoder[cp]));
        }
        return Trim(bytes);
    }

private:
    std::vector<std::string> reverseVocab;
    uint8_t byteDecoder[512]{};

    void generateByteDecoder() {
        std::fill(std::begin(byteDecoder), std::end(byteDecoder), 0);
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
        // Embedding lookups are tiny and Tencent's reference implementation keeps them on CPU.
        embedToken.opt.num_threads = 2;
        embedPosition.opt.num_threads = 2;

        if (!loadNet(fbank, "whisper_base_fbank.ncnn.param", "whisper_base_fbank.ncnn.bin", error)) return false;
        if (!loadNet(encoder, "whisper_base_encoder.ncnn.param", "whisper_base_encoder.ncnn.bin", error)) return false;
        if (!loadNet(embedToken, "whisper_base_embed_token.ncnn.param", "whisper_base_embed_token.ncnn.bin", error)) return false;
        if (!loadNet(embedPosition, "whisper_base_embed_position.ncnn.param", "whisper_base_embed_position.ncnn.bin", error)) return false;
        if (!loadNet(decoder, "whisper_base_decoder.ncnn.param", "whisper_base_decoder.ncnn.bin", error)) return false;
        // The official release ships proj_out and embed_token with byte-identical weight blobs.
        // Reusing the verified embed-token binary saves ~53 MB of first-download/storage cost.
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

    int detectLanguage(const float* samples, int sampleCount) const {
        ncnn::Mat features;
        if (extractFbank(samples, sampleCount, features) != 0) return -1;
        ncnn::Mat encoded;
        if (runEncoder(features, encoded) != 0) return -1;
        ncnn::Mat logits;
        std::vector<ncnn::Mat> cache;
        if (runDecoderPrefill({kTokenStartOfTranscript}, encoded, logits, cache) != 0) return -1;
        if (logits.empty() || logits.w <= kTokenLangLast) return -1;
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

    std::vector<CaptionSegment> transcribeChunk(
        const float* samples,
        int sampleCount,
        int languageIndex,
        int64_t chunkOffsetUs
    ) const {
        std::vector<CaptionSegment> result;
        if (languageIndex < 0 || languageIndex >= kLanguageCount || sampleCount <= 0) return result;

        ncnn::Mat features;
        if (extractFbank(samples, sampleCount, features) != 0) return result;
        ncnn::Mat encoded;
        if (runEncoder(features, encoded) != 0) return result;

        std::vector<int> tokens = {
            kTokenStartOfTranscript,
            kTokenLangFirst + languageIndex,
            kTokenTranscribe,
        };
        std::vector<ncnn::Mat> cache;
        for (int step = 0; step < kMaxDecodedTokens; ++step) {
            ncnn::Mat logits;
            std::vector<ncnn::Mat> nextCache;
            const int status = step == 0
                ? runDecoderPrefill(tokens, encoded, logits, nextCache)
                : runDecoderStep(tokens, encoded, logits, cache, nextCache);
            if (status != 0 || logits.empty()) break;

            const int next = argmax(logits);
            if (next < 0 || next == kTokenEndOfText || next == kTokenNoCaptions) break;
            tokens.push_back(next);
            cache.swap(nextCache);
        }

        const int64_t durationUs = static_cast<int64_t>(sampleCount) * 1000000LL / kSampleRate;
        std::vector<int> textTokens;
        int64_t startUs = -1;
        bool sawTimestamp = false;

        for (size_t i = 3; i < tokens.size(); ++i) {
            const int token = tokens[i];
            if (token >= kTokenTimestampFirst && token <= kTokenTimestampLast) {
                const int64_t timestampUs = static_cast<int64_t>(token - kTokenTimestampFirst) * 20000LL;
                if (startUs < 0) {
                    startUs = timestampUs;
                    textTokens.clear();
                } else {
                    sawTimestamp = true;
                    const int64_t localStart = std::clamp<int64_t>(startUs, 0, durationUs);
                    const int64_t localEnd = std::clamp<int64_t>(timestampUs, 0, durationUs);
                    const std::string text = tokenizer.decodeText(textTokens);
                    if (!text.empty() && localEnd > localStart) {
                        result.push_back({chunkOffsetUs + localStart, chunkOffsetUs + localEnd, text});
                    }
                    startUs = -1;
                    textTokens.clear();
                }
            } else if (token >= 0 && token < kTokenEndOfText) {
                textTokens.push_back(token);
            }
        }

        // Some devices/models may emit text without timestamp token pairs under greedy decoding.
        // Keep the transcript rather than losing it; timing is then bounded to the decoded chunk.
        if (result.empty()) {
            std::vector<int> plainTokens;
            for (size_t i = 3; i < tokens.size(); ++i) {
                const int token = tokens[i];
                if (token >= 0 && token < kTokenEndOfText) plainTokens.push_back(token);
            }
            const std::string text = tokenizer.decodeText(plainTokens);
            if (!text.empty()) result.push_back({chunkOffsetUs, chunkOffsetUs + durationUs, text});
        }

        __android_log_print(
            ANDROID_LOG_INFO,
            kTag,
            "GPU chunk done: samples=%d lang=%s timestamped=%d segments=%zu",
            sampleCount,
            kLanguageCodes[languageIndex],
            sawTimestamp ? 1 : 0,
            result.size()
        );
        return result;
    }

private:
    std::string path(const char* name) const {
        return modelDir + "/" + name;
    }

    void configureGpuNet(ncnn::Net& net) {
        net.opt.use_vulkan_compute = true;
        net.opt.num_threads = 2;
        // Match Tencent's official Whisper ncnn reference: keep storage/arithmetic FP32 for stable
        // multilingual recognition across Mali/Adreno/PowerVR Vulkan drivers.
        net.opt.use_fp16_packed = false;
        net.opt.use_fp16_storage = false;
        net.opt.use_fp16_arithmetic = false;
        net.set_vulkan_device(vkdev);
    }

    bool loadNet(ncnn::Net& net, const char* paramName, const char* binName, std::string& error) {
        const std::string param = path(paramName);
        const std::string bin = path(binName);
        if (net.load_param(param.c_str()) != 0) {
            error = std::string("Could not load ") + paramName;
            return false;
        }
        if (net.load_model(bin.c_str()) != 0) {
            error = std::string("Could not load ") + binName;
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

    static int argmax(const ncnn::Mat& logits) {
        if (logits.empty() || logits.w <= 0) return -1;
        const float* values = logits;
        int best = 0;
        float bestValue = values[0];
        for (int i = 1; i < logits.w; ++i) {
            if (values[i] > bestValue) {
                bestValue = values[i];
                best = i;
            }
        }
        return best;
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

bool HasSpeechEnergy(const float* samples, int count) {
    if (samples == nullptr || count <= 0) return false;
    double absoluteSum = 0.0;
    float peak = 0.f;
    const int stride = std::max(1, count / 8000);
    int measured = 0;
    for (int i = 0; i < count; i += stride) {
        const float value = std::fabs(samples[i]);
        absoluteSum += value;
        peak = std::max(peak, value);
        ++measured;
    }
    const double meanAbs = measured > 0 ? absoluteSum / measured : 0.0;
    return peak >= 0.008f && meanAbs >= 0.0008;
}

bool EnsureEngine(const std::string& modelDir, std::string& error) {
    if (gEngine && gEngine->modelDir == modelDir) return true;
    auto engine = std::make_unique<WhisperNcnnEngine>();
    if (!engine->load(modelDir, error)) return false;
    gEngine = std::move(engine);
    return true;
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

    std::lock_guard<std::mutex> guard(gEngineMutex);
    std::string error;
    if (!EnsureEngine(modelDir, error)) {
        ThrowJava(env, "java/lang/IllegalStateException", "GPU_BACKENDS_UNAVAILABLE: " + error);
        return nullptr;
    }

    std::vector<CaptionSegment> segments;
    int languageIndex = requestedLanguage.empty() || requestedLanguage == "auto"
        ? -1
        : FindLanguageIndex(requestedLanguage);
    if (languageIndex < 0 && !(requestedLanguage.empty() || requestedLanguage == "auto")) {
        ThrowJava(env, "java/lang/IllegalArgumentException", "Unsupported Whisper language: " + requestedLanguage);
        return nullptr;
    }

    int offset = 0;
    while (offset < sampleCount) {
        const int count = std::min(kMaxChunkSamples, static_cast<int>(sampleCount) - offset);
        const float* chunk = samples.data() + offset;
        if (!HasSpeechEnergy(chunk, count)) {
            offset += count;
            continue;
        }
        if (languageIndex < 0) {
            languageIndex = gEngine->detectLanguage(chunk, count);
            if (languageIndex < 0 || languageIndex >= kLanguageCount) languageIndex = FindLanguageIndex("en");
            __android_log_print(
                ANDROID_LOG_INFO,
                kTag,
                "Auto Detect resolved language=%s",
                kLanguageCodes[languageIndex]
            );
        }
        const int64_t offsetUs = static_cast<int64_t>(offset) * 1000000LL / kSampleRate;
        std::vector<CaptionSegment> local = gEngine->transcribeChunk(chunk, count, languageIndex, offsetUs);
        segments.insert(segments.end(), local.begin(), local.end());
        offset += count;
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
        "GPU transcription complete: backend=%s samples=%d segments=%zu",
        BackendLabel().c_str(),
        static_cast<int>(sampleCount),
        segments.size()
    );
    return result;
}
