# Third-party notices

This file records third-party components intentionally introduced by Digitor Auto CC.
It is not legal advice; release packaging should retain the applicable notices and license texts.

## sherpa-onnx Android runtime

- Project: `k2-fsa/sherpa-onnx`
- Version: `v1.13.8`
- Purpose: on-device streaming ASR runtime and Android JNI bindings
- License: Apache License 2.0

The complete Apache License 2.0 text is packaged in the app at
`assets/legal/APACHE-2.0.txt`.

## Bengali Auto CC model

- Model: `alphacep/vosk-model-small-streaming-bn`
- Pinned model revision: `dfabeea5eee1f33d81436826d0575d8cfd64bd1d`
- Digitor files: encoder, decoder, joiner and tokens
- Purpose: dedicated Bengali streaming Zipformer2 recognition
- License: Apache License 2.0

The model is downloaded on first use into private app storage and is not bundled in the APK.

## English Auto CC model

- Model: `csukuangfj/sherpa-onnx-streaming-zipformer-en-20M-2023-02-17`
- Pinned model revision: `d42f2d9f7ca24806fb667456a18a9f1b60f70d16`
- Digitor files: INT8 encoder, INT8 decoder, INT8 joiner and tokens
- Purpose: compact English streaming recognition
- License: Apache License 2.0

The model is downloaded on first use into private app storage and is not bundled in the APK.

## ONNX Runtime

sherpa-onnx uses ONNX Runtime internally. Digitor already uses the Android ONNX Runtime dependency for
its PP-Matting reliability fallback. ONNX Runtime is distributed under the MIT License; retain the
upstream ONNX Runtime notices/license with production distributions as applicable.

## Release note

Auto CC V79 does **not** use Whisper, WhisperKit, ggml, Qualcomm QNN, or the former WhisperKit FFmpeg
runtime. Speech recognition uses the official sherpa-onnx Android runtime on CPU with dedicated
Bengali and English Zipformer models. Audio/video is not uploaded to a speech service. Internet
permission is used only to download the selected model on first use; the model is then reused from
private app storage.
