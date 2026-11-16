# Third-party notices

The [MIT license](LICENSE) applies to original Unrabbit code and documentation. Third-party components retain their own licenses.

## Included in this repository

| Component | Files | License |
| --- | --- | --- |
| IBM Plex Sans, © 2017 IBM Corp. | `android/app/src/main/res/font/` | [SIL Open Font License 1.1](android/app/src/main/assets/OFL.txt); reserved font name: Plex |
| Gradle Wrapper | `android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/` | [Apache License 2.0](LICENSES/Apache-2.0.txt); existing copyright notices retained |

Upstream: [IBM Plex](https://github.com/IBM/plex), [Gradle](https://github.com/gradle/gradle).

## Resolved by the build

VM speech uses [faster-whisper](https://github.com/SYSTRAN/faster-whisper), [CTranslate2](https://github.com/OpenNMT/CTranslate2), PyAV and their dependencies, installed from `vm/requirements-speech.txt`. The multilingual [Whisper base conversion](https://huggingface.co/Systran/faster-whisper-base) is downloaded at a pinned revision by `speech-setup`; faster-whisper includes Silero VAD. These packages and models retain their upstream licenses. No speech weights or native recognition binaries are bundled in the Android APK or checked into this repository.

The app uses [Kotlin](https://github.com/JetBrains/kotlin), [AndroidX / CameraX](https://android.googlesource.com/platform/frameworks/support/) and [ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded), including ZXing Core. These components are licensed under Apache 2.0; their versions are specified in the Gradle build files. Test dependencies include JUnit and Robolectric under their respective upstream licenses.

The terminal wizard uses [qrcode](https://github.com/lincolnloop/python-qrcode) and [Pillow](https://github.com/python-pillow/Pillow); these packages are installed separately and are not vendored here.

The optional Codex container resolves [Codex CLI](https://learn.chatgpt.com/docs/codex/cli) and the Node.js runtime during its Docker build. These binaries are not vendored in this repository and retain their upstream licenses.

The Android SSH client uses [mwiede/JSch](https://github.com/mwiede/jsch), BSD 3-Clause. Its [license](LICENSES/JSch.txt) is also included in the app assets. The VM SSH endpoint uses [AsyncSSH](https://github.com/ronf/asyncssh), under EPL 2.0 or GPL 2.0-or-later with the Classpath Exception; its cryptography dependencies retain their upstream licenses. VM packages are resolved separately and are not vendored here.
