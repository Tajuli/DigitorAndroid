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

## Bengali Auto CC language pack

- Model: `alphacep/vosk-model-small-streaming-bn`
- Pinned model revision: `dfabeea5eee1f33d81436826d0575d8cfd64bd1d`
- Digitor files: encoder, decoder, joiner and tokens
- Purpose: dedicated Bengali streaming Zipformer2 recognition in Fast and Accurate modes
- License: Apache License 2.0

## English Auto CC language pack

- Model: `csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17`
- Pinned model revision: `d42f2d9f7ca24806fb667456a18a9f1b60f70d16`
- Digitor files: INT8 encoder, INT8 decoder, INT8 joiner and tokens
- Purpose: compact English streaming Zipformer recognition in Fast and Accurate modes
- License: Apache License 2.0

## Chinese Auto CC language pack

- Model: `csukuangfj/sherpa-onnx-streaming-zipformer-zh-14M-2023-02-23`
- Pinned model revision: `204ad334e2e683fd295359930cc16fc0432a23ac`
- Digitor files: INT8 encoder, decoder, INT8 joiner and tokens
- Purpose: compact Chinese streaming Zipformer recognition in Fast and Accurate modes
- License: Apache License 2.0

## Korean Auto CC language pack

- Model: `k2-fsa/sherpa-onnx-streaming-zipformer-korean-2024-06-16`
- Pinned model revision: `023c8279ae8ac55719b07eee0ad8bc59889973fb`
- Digitor files: INT8 encoder, decoder, INT8 joiner and tokens
- Purpose: Korean streaming Zipformer recognition in Fast and Accurate modes
- License: Apache License 2.0

## French Auto CC language pack

- Model: `shaojieli/sherpa-onnx-streaming-zipformer-fr-2023-04-14`
- Pinned model revision: `3db9565d9633758d6b87b9a7b3dc09ebfb6b2c73`
- Digitor files: INT8 encoder, decoder, INT8 joiner and tokens
- Purpose: French streaming Zipformer recognition in Fast and Accurate modes
- License: Apache License 2.0

The language packs are not bundled in the APK. Users explicitly download only the packs they want,
and installed packs are cached in private app storage. Packs can be deleted later from Manage Languages.

## ONNX Runtime

- Android artifact: `com.microsoft.onnxruntime:onnxruntime-android:1.27.0`
- Purpose: sherpa-onnx native inference plus Digitor's existing PP-Matting reliability fallback
- License: MIT License

The sherpa Android JNI binary and packaged ONNX Runtime must remain on a compatible native ABI
version; Digitor pins the pair used by Auto CC rather than allowing an arbitrary runtime upgrade.

## Release note

Auto CC V86 does **not** use Whisper, WhisperKit, ggml, Qualcomm QNN, Omnilingual ASR, or the former
WhisperKit FFmpeg runtime. Recognition is performed locally with downloadable Zipformer language
packs. The first international release exposes Bengali, English, Chinese, Korean and French packs.
Fast mode uses greedy decoding. Accurate mode adds speech-level normalization, silence-aware
segmentation, incremental streaming input, a short tail flush and wider modified-beam search.
Global language detection is intentionally not implemented by running every installed model; the
legacy Auto option only compares Bengali and English when both packs are installed. User audio/video
is not uploaded to a speech service. Internet permission is used only for explicit language-pack downloads.
