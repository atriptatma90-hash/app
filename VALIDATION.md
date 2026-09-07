# Final source validation

Produced: a source-only Android project ZIP and GitHub Actions APK build workflow.

The preparation environment had no Android SDK, Gradle compiler toolchain, emulator or connected Android phone. Official dependency downloads failed. The file workspace also reset; the final native-widget project was recreated. Earlier browser UI tests do not validate this version and are not reported as passing tests for it.

Executed here: XML parsing, source/resource presence checks, permission/native-packaging checks, license/documentation presence and ZIP integrity checks. See validation/source-checks.txt.

NOT executed here:
- Kotlin/Gradle compilation, lint or Android unit/instrumentation tests.
- APK generation, signing or signature verification.
- Package Installer installation or native startup.
- Native UI rendering/screenshots/visual inspection. No visual-QA pass is claimed.
- Native Python/yt-dlp/FFmpeg execution, live-site downloading, conversion or playback.
- Android background, permissions, cancellation, MediaStore recovery or device compatibility.

The included workflow is intended to perform build and basic emulator gates but has NOT been run in this chat. A build/test failure must be fixed before claiming an installable verified APK.
