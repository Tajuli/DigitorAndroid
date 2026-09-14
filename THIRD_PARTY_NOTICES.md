# Third-party notices

This file records third-party components intentionally introduced by Digitor Auto CC.
It is not legal advice; release packaging should retain the applicable notices and license texts.

## sherpa-onnx Android runtime

- Project: `k2-fsa/sherpa-onnx`
- Version: `v1.13.4`
- Purpose: on-device streaming/offline ASR runtime and Android JNI bindings
- License: Apache License 2.0

The complete Apache License 2.0 text is packaged in the app at
`assets/legal/APACHE-2.0.txt`.

## Bengali Fast Auto CC model

- Model: `alphacep/vosk-model-small-streaming-bn`
- Pinned model revision: `dfabeea5eee1f33d81436826d0575d8cfd64bd1d`
- Digitor files: encoder, decoder, joiner and tokens
- Purpose: dedicated Bengali streaming Zipformer2 recognition in Fast/fallback mode
- License: Apache License 2.0

The model is downloaded on first use into private app storage and is not bundled in the APK.

## English Fast Auto CC model

- Model: `csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17`
- Pinned model revision: `d42f2d9f7ca24806fb667456a18a9f1b60f70d16`
- Digitor files: INT8 encoder, INT8 decoder, INT8 joiner and tokens
- Purpose: compact English streaming recognition in Fast/fallback mode
- License: Apache License 2.0

The model is downloaded on first use into private app storage and is not bundled in the APK.

## Omnilingual ASR v2 Accurate Auto CC model

- Distribution: `Edison2ST/sherpa-onnx-omnilingual-asr-1600-languages-ctc-v2`
- Pinned revision: `117cc213211720a50668777a4b2c390dedeb5718`
- Archive: `sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-v2-int8-2026-02-05.tar.bz2`
- Verified SHA-256: `b72bef9be75862684098e722d79fefecf9f10fefd3a0b2950738977b4c6b4147`
- Digitor files after extraction: `model.int8.onnx` and `tokens.txt`
- Purpose: higher-accuracy multilingual/Bengali/English offline recognition in Accurate mode
- License: Apache License 2.0

The archive downloads only when Accurate mode is first used, is checksum-verified, is extracted into
private app storage, and is deleted after successful installation. The model itself is not bundled in
the APK.

## Apache Commons Compress

- Artifact: `org.apache.commons:commons-compress:1.28.0`
- Purpose: local extraction of the Accurate model's `.tar.bz2` archive
- License: Apache License 2.0

## ONNX Runtime

- Android artifact: `com.microsoft.onnxruntime:onnxruntime-android:1.27.0`
- Purpose: sherpa-onnx native inference plus Digitor's existing PP-Matting reliability fallback
- License: MIT License

The sherpa Android JNI binary and the packaged ONNX Runtime must remain on a compatible native ABI
version; Digitor pins the pair used by Auto CC rather than allowing an arbitrary runtime upgrade.

## Release note

Auto CC V80 does **not** use Whisper, WhisperKit, ggml, Qualcomm QNN, or the former WhisperKit FFmpeg
runtime. Fast mode uses dedicated Bengali/English Zipformer models. Accurate mode uses the optional
Omnilingual ASR v2 300M INT8 model. Speech recognition stays on-device; audio/video is not uploaded to
a speech service. Internet permission is used only to download a selected model on first use, and
models are reused from private app storage afterwards.
