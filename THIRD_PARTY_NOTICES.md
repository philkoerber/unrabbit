# Third-party notices

The [MIT license](LICENSE) applies to original Unrabbit code and documentation. Third-party components retain their own licenses.

## Included in this repository

| Component | Files | License |
| --- | --- | --- |
| IBM Plex Sans, © 2017 IBM Corp. | `android/app/src/main/res/font/` | [SIL Open Font License 1.1](android/app/src/main/assets/OFL.txt); reserved font name: Plex |
| Gradle Wrapper | `android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/` | [Apache License 2.0](LICENSES/Apache-2.0.txt); existing copyright notices retained |

Upstream: [IBM Plex](https://github.com/IBM/plex), [Gradle](https://github.com/gradle/gradle).

## Resolved by the build

Android chat formatting uses [commonmark-java 0.32.0](https://github.com/commonmark/commonmark-java/tree/commonmark-parent-0.32.0), under BSD 2-Clause. Its [license](android/app/src/main/assets/CommonMark-LICENSE.txt) is included in the APK assets. Only its core parser is used; displayed links and image labels do not fetch external content.

VM speech uses [faster-whisper](https://github.com/SYSTRAN/faster-whisper), [CTranslate2](https://github.com/OpenNMT/CTranslate2), PyAV and their dependencies, installed from `vm/requirements-speech.txt`. The multilingual [Whisper base conversion](https://huggingface.co/Systran/faster-whisper-base) is downloaded at a pinned revision by `speech-setup`; faster-whisper includes Silero VAD. These packages and models retain their upstream licenses. No speech weights or native recognition binaries are bundled in the Android APK or checked into this repository.

Reply synthesis uses [Piper 1.8.0](https://github.com/OHF-Voice/piper1-gpl/tree/v1.8.0), under **GPL-3.0-or-later**, with bundled eSpeak NG and separately resolved ONNX Runtime/PyAV dependencies. These are VM dependencies, not Android binaries or MIT-licensed project content. Distributions of these dependencies must retain their upstream licenses and applicable source obligations.

`tts-setup` downloads **en_US-amy-medium** and **de_DE-ramona-low** from [rhasspy/piper-voices](https://huggingface.co/rhasspy/piper-voices/tree/e9ab671f2a0e50a701b34361136ba3e6a3a9eb0c), pinned with SHA-256 in `vm/unrabbit_vm/voices.json`; it retains each `MODEL_CARD`. Amy's model card points to [Mycroft Mimic 3 voices](https://github.com/MycroftAI/mimic3-voices) for its source dataset and license. It is fine-tuned from the `hifi_tts_92` base, the voice of **Cori Samuel**, reader 92 of the **Hi-Fi Multi-Speaker English TTS Dataset**, © NVIDIA Corporation, [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/), by Evelina Bakhturina, Vitaly Lavrukhin, Boris Ginsburg and Yang Zhang ([dataset](https://www.openslr.org/109/), [paper](https://arxiv.org/abs/2104.01497)). Ramona is trained from scratch on the [M-AILABS Speech Dataset](https://www.caito.de/2019/01/03/the-m-ailabs-speech-dataset/); its model card refers to that source for the dataset license. These upstream terms apply separately from Unrabbit's MIT license. Generated samples are synthetic speech. Unrabbit does not relicense these weights. See Piper's [voice licensing notes](https://github.com/OHF-Voice/piper1-gpl/blob/main/docs/LICENSING.md).

The app uses [Kotlin](https://github.com/JetBrains/kotlin), [AndroidX / CameraX](https://android.googlesource.com/platform/frameworks/support/) and [ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded), including ZXing Core. These components are licensed under Apache 2.0; their versions are specified in the Gradle build files. Test dependencies include JUnit and Robolectric under their respective upstream licenses.

The terminal wizard uses [qrcode](https://github.com/lincolnloop/python-qrcode) and [Pillow](https://github.com/python-pillow/Pillow); these packages are installed separately and are not vendored here.

The optional Codex container resolves [Codex CLI](https://learn.chatgpt.com/docs/codex/cli) and the Node.js runtime during its Docker build. These binaries are not vendored in this repository and retain their upstream licenses.

The Android SSH client uses [mwiede/JSch](https://github.com/mwiede/jsch), BSD 3-Clause. Its [license](LICENSES/JSch.txt) is also included in the app assets. The VM SSH endpoint uses [AsyncSSH](https://github.com/ronf/asyncssh), under EPL 2.0 or GPL 2.0-or-later with the Classpath Exception; its cryptography dependencies retain their upstream licenses. VM packages are resolved separately and are not vendored here.
