# Release Artifacts

This repository stores the source snapshot for rollback and future development.

Included in git:
- Android and Windows source code
- project docs and scripts
- packaging configuration

Excluded from git:
- `key/mobile-speaker-release.jks`
  - private Android signing key, must never be published to a public repository
- `.adb-home/`
- `.gradle-user/`
- `apps/android-app/app/build/`
- `apps/pc-electron/release/`
  - contains generated release outputs, including a Windows installer larger than GitHub's normal git file limit

Current local release artifacts at snapshot time:
- Android signed release APK:
  - `C:\Program_Data\Codex\mobile-speaker\apps\android-app\app\build\outputs\apk\release\app-release.apk`
- Windows installer:
  - `C:\Program_Data\Codex\mobile-speaker\apps\pc-electron\release\mobile-speaker-pc-setup-0.1.0.exe`
- Windows unpacked executable:
  - `C:\Program_Data\Codex\mobile-speaker\apps\pc-electron\release\win-unpacked\mobile-speaker-pc.exe`

If you want these binaries versioned publicly later, use GitHub Releases or Git LFS rather than normal git history.
