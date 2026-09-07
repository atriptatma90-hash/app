# Required release acceptance tests

These are requirements, NOT claims that the tests have passed.

- [ ] Kotlin unit tests, Android lint and build pass.
- [ ] APK signature verifies with apksigner.
- [ ] Fresh Package Installer install and app launch succeed.
- [ ] Test Android 10 and recent Android 15/16 on real ARM devices.
- [ ] Test a real 16 KB-page-size arm64 device and any 32-bit ABI to be distributed.
- [ ] Direct MP4/image/PDF/ZIP bytes match and files open/share normally.
- [ ] Authorized real media-site videos download and play with sound.
- [ ] MP3 extraction and merged MKV output play in another app.
- [ ] Share-in populates input but never auto-downloads.
- [ ] Notification permission accepted AND refused on Android 13+.
- [ ] Screen-off/background/OEM battery restrictions and mobile data tested.
- [ ] Network loss, expired URL, redirects, HTTP errors and TLS failures are visible.
- [ ] Full/unavailable storage never falsely says Completed or destroys an existing file.
- [ ] Cancel during preparation, transfer, merge and export.
- [ ] Force-stop/process death/reboot recovery without duplicated or deleted committed exports.
- [ ] Foreground-service time-limit callback stops promptly.
- [ ] Large files, including >4 GB where applicable.
- [ ] Engine update success and failure preserve saved media.
- [ ] Same-key APK upgrade with higher versionCode.
- [ ] TalkBack, large text, keyboard, orientation, dark mode and display cutouts.
- [ ] All native dependency notices and corresponding-source obligations satisfied.

No guarantee is made for every site, codec, device, network or OEM background policy.
