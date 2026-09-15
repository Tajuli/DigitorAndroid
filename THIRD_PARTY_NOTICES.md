# Third-party notices

This file records third-party components intentionally introduced by Digitor Auto CC.
It is not legal advice; release packaging should retain the applicable notices and license texts.

## sherpa-onnx Android runtime

- Project: `k2-fsa/sherpa-onnx`
- Version: `v1.13.4`
- Purpose: on-device streaming ASR runtime and Android JNI bindings
- License: Apache License 2.0

The complete Apache License 2.0 text is packaged in the app at
`assets/legal/APACHE-2.0.txt`.

## International Auto CC language packs

Digitor uses English as the default Auto CC language pack. The English pack is installed first by
an explicit user download. Bengali, Chinese, Korean, and French are optional packs that users can
add or remove later from Manage Languages. Speech models are not bundled in the APK.

### English (default)

- Model: `csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17`
- Pinned model revision: `d42f2d9f7ca24806fb667456a18a9f1b60f70d16`
- Purpose: compact default English streaming Zipformer recognition
- License: Apache License 2.0

### Bengali

- Model: `alphacep/vosk-model-small-streaming-bn`
- Pinned model revision: `dfabeea5eee1f33d81436826d0575d8cfd64bd1d`
- Purpose: dedicated Bengali streaming Zipformer2 recognition
- License: Apache License 2.0

### Chinese

- Model: `csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23`
- Pinned model revision: `204ad334e2e683fd295359930cc16fc0432a23ac`
- Purpose: compact Chinese streaming Zipformer recognition
- License: Apache License 2.0

### Korean

- Model: `k2-fsa/sherpa-onnx-streaming-zipformer-korean-2024-06-16`
- Pinned model revision: `023c8279ae8ac55719b07eee0ad8bc59889973fb`
- Purpose: Korean streaming Zipformer recognition
- License: Apache License 2.0

### French

- Model: `shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14`
- Pinned model revision: `3db9565d9633758d6b87b9a7b3dc09ebfb6b2c73`
- Purpose: French streaming Zipformer recognition
- License: Apache License 2.0

The model files are downloaded only after explicit user action into private app storage. English is
the non-deletable base Auto CC pack in the V86 UI; the four additional language packs can be added
or removed independently.

## ONNX Runtime

- Android artifact: `com.microsoft.onnxruntime:onnxruntime-android:1.27.0`
- Purpose: sherpa-onnx native inference plus Digitor's existing PP-Matting reliability fallback
- License: MIT License

The sherpa Android JNI binary and packaged ONNX Runtime must remain on a compatible native ABI
version; Digitor pins the pair used by Auto CC rather than allowing an arbitrary runtime upgrade.

## Release note

Auto CC V86 does **not** use Whisper, WhisperKit, ggml, Qualcomm QNN, Omnilingual ASR, or the former
WhisperKit FFmpeg runtime. Fast and Accurate both use Zipformer models. Accurate mode adds
speech-level normalization, silence-aware segmentation, incremental streaming input, a short tail
flush, and wider modified-beam search. Speech recognition stays on-device; audio/video is not
uploaded to a speech service. Internet permission is used only for explicit language-pack downloads.
