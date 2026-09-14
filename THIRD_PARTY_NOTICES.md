# Third-party notices

This file records third-party components intentionally introduced by Digitor Auto CC.
It is not legal advice; release packaging should retain the applicable notices and license texts.

## WhisperKit Android 0.3.3

- Project: `argmaxinc/WhisperKitAndroid`
- Purpose: on-device automatic speech recognition
- License: MIT
- Copyright: Copyright (c) 2024 argmax, inc.

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

## OpenAI Whisper

- Project: `openai/whisper`
- Purpose: Whisper speech-recognition architecture/model family used by the Auto CC runtime
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

## Qualcomm QNN Runtime / LiteRT delegate 2.34.0

Digitor packages `com.qualcomm.qti:qnn-runtime:2.34.0` and
`com.qualcomm.qti:qnn-litert-delegate:2.34.0` because the published WhisperKit Android
native binary has a hard dynamic-library dependency on `libQnnTFLiteDelegate.so`.
Without those AARs, `System.loadLibrary("whisperkit_jni")` fails before Digitor can
select either the GPU or CPU inference backend.

These Qualcomm runtime components are **not MIT/open-source software**. Maven Central
identifies them under Qualcomm's license terms. Keep Qualcomm's copyright/proprietary
notices and review the exact redistribution terms before a Play Store production
release. They must not be described as part of Digitor's MIT/open-source dependency set.

If Digitor's release policy requires every Auto CC runtime component to be OSI-style
open source, this WhisperKit binary path should be replaced rather than shipping the
Qualcomm runtime.

## FFmpeg libraries used inside WhisperKit Android

WhisperKit Android's native build links FFmpeg `avformat`, `avcodec`, `avutil`, and
`swresample` as **shared libraries** and its build script configures FFmpeg with
`--disable-static --enable-shared`. Those FFmpeg components are generally distributed
under LGPL-2.1-or-later when no GPL-only options are enabled.

For a Play Store release, retain the FFmpeg copyright/license notice, distribute a
copy of the applicable LGPL license with the app or accompanying legal notices, and
provide the corresponding FFmpeg source (or an equivalent compliant source offer)
for the exact binary version bundled by the pinned WhisperKit artifact. Do not switch
WhisperKit's FFmpeg build to GPL/nonfree options without re-checking the app's licensing.

## Release note

The Auto CC feature does not require a paid/cloud speech API. Model inference runs on
the user's device. The first use downloads the selected model from the model host and
then reuses the cached copy.
