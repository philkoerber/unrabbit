# Third-party notices

The [MIT license](LICENSE) applies to original Unrabbit code and documentation. Third-party components retain their own licenses.

## Included in this repository

| Component | Files | License |
| --- | --- | --- |
| IBM Plex Sans, © 2017 IBM Corp. | `android/app/src/main/res/font/` | [SIL Open Font License 1.1](android/app/src/main/assets/OFL.txt); reserved font name: Plex |
| Gradle Wrapper | `android/gradlew`, `android/gradlew.bat`, `android/gradle/wrapper/` | [Apache License 2.0](LICENSES/Apache-2.0.txt); existing copyright notices retained |

Upstream: [IBM Plex](https://github.com/IBM/plex), [Gradle](https://github.com/gradle/gradle).

## Resolved by the build

The app uses [Kotlin](https://github.com/JetBrains/kotlin), [AndroidX / CameraX](https://android.googlesource.com/platform/frameworks/support/) and [ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded), including ZXing Core. These components are licensed under Apache 2.0; their versions are specified in the Gradle build files. Test dependencies include JUnit and Robolectric under their respective upstream licenses.

The optional QR generator uses [qrcode](https://github.com/lincolnloop/python-qrcode) and [Pillow](https://github.com/python-pillow/Pillow); these packages are installed separately and are not vendored here.
