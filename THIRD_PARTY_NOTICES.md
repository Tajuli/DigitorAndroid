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

## Bengali Auto CC model

- Model: `alphacep/vosk-model-small-streaming-bn`
- Pinned model revision: `dfabeea5eee1f33d81436826d0575d8cfd64bd1d`
- Digitor files: encoder, decoder, joiner and tokens
- Purpose: dedicated Bengali streaming Zipformer2 recognition in Fast and Accurate modes
- License: Apache License 2.0

The model is downloaded on first use into private app storage and is not bundled in the APK.

## English Auto CC model

- Model: `csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17`
- Pinned model revision: `d42f2d9f7ca24806fb667456a18a9f1b60f70d16`
- Digitor files: INT8 encoder, INT8 decoder, INT8 joiner and tokens
- Purpose: compact English streaming Zipformer recognition in Fast and Accurate modes
- License: Apache License 2.0

The model is downloaded on first use into private app storage and is not bundled in the APK.

## ONNX Runtime

- Android artifact: `com.microsoft.onnxruntime:onnxruntime-android:1.27.0`
- Purpose: sherpa-onnx native inference plus Digitor's existing PP-Matting reliability fallback
- License: MIT License

The sherpa Android JNI binary and packaged ONNX Runtime must remain on a compatible native ABI
version; Digitor pins the pair used by Auto CC rather than allowing an arbitrary runtime upgrade.

## Release note

Auto CC V82 does **not** use Whisper, WhisperKit, ggml, Qualcomm QNN, Omnilingual ASR, or the former
WhisperKit FFmpeg runtime. Fast and Accurate both use Zipformer models. Accurate mode improves the
same Bengali/English Zipformer path with shorter balanced recognition windows and modified beam
search rather than downloading a second large multilingual model. Speech recognition stays on-device;
audio/video is not uploaded to a speech service. Internet permission is used only to download the
selected Zipformer model on first use, and models are reused from private app storage afterwards.
