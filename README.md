# DownloadVerse Android — native source preview

**This ZIP contains source code, not an APK.** No APK was compiled, signed, installed or device-tested here. The Android SDK/compiler and build dependencies were unavailable. File storage also reset during preparation, so the final source was recreated with native Android widgets. Earlier browser-prototype checks do not apply to this final native UI.

## Get an APK without Android Studio

Read **START-HERE.txt**. Upload the extracted project contents, including `.github`, into the root of a GitHub repository you control. Open **Actions → Build Android APK → Run workflow**. If the build and emulator tests pass, download **DownloadVerse-debug-apk**, unzip that artifact, then install its `.apk` using Android Package Installer.

GitHub supplies JDK 17, Gradle 8.13 and Android SDK 35. You need a browser, a GitHub account, internet and permission/quota to use Actions; Android Studio is optional. A computer browser is easiest for uploading folders. Do not make private content public solely to get build minutes.

**The workflow has not been run here.** Source/API/native dependency problems may still need fixes before it produces an APK. Do not describe this as issue-free, tested or production-ready. A passing emulator smoke test is not proof that every website or Android device works.

## Intended app scope

- Android 10+ (minSdk 29), targetSdk 35.
- Universal APK requests arm64-v8a, armeabi-v7a and x86_64 native libraries.
- Native Android input, queue and controls. No Windows EXE, PowerShell, browser server or separate Python installation after the APK is built.
- Paste up to 20 HTTP(S) links, one per line; share links into the app without automatic downloading.
- Persistent history, cancel/retry, open/share completed downloads.
- Direct files use Android Download Manager and public Downloads/DownloadVerse.
- Supported media-site URLs use yt-dlp/Python and FFmpeg through youtubedl-android 0.18.1. Gradle bundles the native engines into the APK; they are not inside this source ZIP.
- Video quality cap and MP3 audio extraction. Original video formats may be kept; separate tracks merge to MKV. MP4 output is NOT guaranteed.
- Media transfers use a foreground service and notification. Final media files are exported using scoped-storage MediaStore, without root or all-files access.

The built universal APK can be much larger than this small source ZIP because it includes multiple native architectures.

## Not Windows feature parity

Desktop Studio editing, clip-only transfer, gallery-dl post/album extraction, authenticated cookie imports, full playlists, selectable Windows executable icons, and Windows setup/repair utilities are not ported. Direct image URLs are supported; gallery/post links only work when yt-dlp supports that particular source. There is no DRM, paywall, login, regional-block or anti-bot bypass.

## Storage, permissions and interruption

Media downloads use private staging while downloading/merging and then copy the complete output into public Downloads via MediaStore. Completion is set only after publishing. Failed jobs retain partial data for Retry; cancellation removes the job's private temporary files. Removing completed history does not delete a completed public file. Keep space for temporary tracks and a final copy, often around twice the final file size or more.

Android may stop tasks after force-stop, reboot, background-service time limits, low storage or OEM battery restrictions. Interrupted media tasks require reopening the app and tapping Retry. No boot receiver or silent restart is included. Android Download Manager handles direct-file background behavior separately.

HTTP links show an unencrypted-traffic confirmation. Mobile data may be used. Direct transfers disallow roaming; media-engine transfers follow the device's current network. TLS verification is not disabled. Non-HTTP schemes, embedded URL credentials, whitespace/control characters and arbitrary user-supplied engine flags are rejected. URLs remain in private history excluded from backup; remove sensitive history when needed. No account, telemetry, advertising or hosted download backend is included.

Engine updates are user-requested upstream stable yt-dlp updates. New versions may introduce Python/JavaScript-runtime or site requirements; test compatibility rather than assuming every update restores every site.

## Optional local build

With JDK 17, Gradle 8.13, SDK Platform 35 and Build Tools 35.0.0 installed:

```sh
gradle --no-daemon testDebugUnitTest lintDebug assembleDebug
```

Debug APK output: app/build/outputs/apk/debug/app-debug.apk.

This source-only package has no Gradle wrapper JAR. The GitHub workflow installs Gradle directly. For Android Studio, configure local Gradle 8.13 or generate a wrapper with `gradle wrapper --gradle-version 8.13` first.

## Release signing

The default workflow output is a signed debug TEST APK. Its signing key may change on subsequent CI runs, requiring uninstall/reinstall and loss of private app data. Do not use changing debug keys for public updates.

For a stable signed release, create and keep your own keystore, then configure repository Actions secrets:
- ANDROID_KEYSTORE_BASE64
- ANDROID_STORE_PASSWORD
- ANDROID_KEY_ALIAS
- ANDROID_KEY_PASSWORD

The workflow creates an additional DownloadVerse-signed-release-apk artifact when those secrets are present. No keystore or password is included in this archive. Never commit a key. Future releases need the same signing identity and an increased versionCode. Signing is not Google certification or approval.

## Validation and licensing

See VALIDATION.md and TESTING.md. Only the final structural checks in validation/source-checks.txt were executed locally. Kotlin compilation, native UI rendering, APK signing, installation and real downloads were NOT performed. The workflow includes unit tests and an API 35 x86_64 emulator gate for launch, engine initialization and an exact-byte local direct download. It does not certify real-site extraction, media playback or real-device ARM/16 KB page-size support.

New Android code: GPL-3.0-or-later; see COPYING. The original Windows MIT notice is preserved in notices/. Linked native dependencies have additional notice/corresponding-source obligations; see THIRD-PARTY-NOTICES.md before distributing binaries. A new geometric arrow icon is used; One Piece character images, supplied custom logos and Windows binaries are deliberately not redistributed.
