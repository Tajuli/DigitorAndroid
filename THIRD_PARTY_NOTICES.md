# Third-party notices

This file records third-party components intentionally introduced by Digitor Auto CC.
It is not legal advice; release packaging should retain the applicable notices and license texts.

## whisper.cpp / ggml (pinned v1.9.4 commit)

- Project: `ggml-org/whisper.cpp`
- Pinned commit: `927cfce34f31707e17f2bff35c349632fb9e2c3a`
- Purpose: on-device Whisper inference, including ggml Vulkan GPU and CPU backends
- License: MIT
- Copyright: Copyright (c) 2023-2026 The ggml authors

MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## OpenAI Whisper models

- Project: `openai/whisper`
- Purpose: Whisper multilingual model family; Digitor downloads the selected converted ggml model on first use
- Model host used by the upstream whisper.cpp downloader: `ggerganov/whisper.cpp` on Hugging Face
- License: MIT
- Copyright: Copyright (c) 2022 OpenAI

MIT License

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## Khronos SPIR-V Headers

- Project: `KhronosGroup/SPIRV-Headers`
- Pinned commit: `04fd3caa1e8267e4d95c806cad901181728e1006`
- Purpose: build-time headers required by ggml's Vulkan shader generator
- Primary source/header license: MIT (the repository also contains documentation/specification files under their stated licenses)

The SPIR-V header sources are fetched only while building the native Vulkan backend. Digitor retains
the upstream notices and does not ship the source repository as an app feature.

## Release note

Auto CC no longer includes WhisperKit, Qualcomm QNN, or WhisperKit's FFmpeg runtime. The speech
engine is built from the pinned whisper.cpp/ggml source and uses Vulkan first with CPU fallback.
No paid/cloud speech API is required. The selected multilingual model is downloaded on first use,
then reused from private app storage; user audio remains on-device.
