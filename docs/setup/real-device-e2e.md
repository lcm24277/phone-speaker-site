# Real Device E2E

This flow validates PC service + Android receiver end-to-end using aligned log markers.

## Prerequisites

- `cargo` in PATH
- `adb` in PATH
- Android app installed on device (`com.mobilespeaker.app`)
- Device and PC in same LAN

## Run

```powershell
cd C:\Program_Data\Codex\mobile-speaker
powershell -ExecutionPolicy Bypass -File .\scripts\run-real-device-e2e.ps1 -PcIp <PC_LAN_IP> -DeviceSerial <optional_adb_serial> -DurationSec 45
```

Artifacts:

- `artifacts/e2e/android-logcat.log`
- `artifacts/e2e/pc-service-stdout.log`
- `artifacts/e2e/pc-service-stderr.log`
- `artifacts/e2e/acceptance-result.json`

## Acceptance checks (automated)

- PC markers:
  - `PC_MDNS_REGISTERED`
  - `PC_CONNECT_OK`
  - `PC_RTP_FIRST_PACKET`
- Android markers:
  - `ANDROID_CONNECT_OK`
  - `ANDROID_RTP_PACKET`
  - `ANDROID_PLAYBACK_PLAYING`
- Time alignment:
  - connect markers delta <= 20s
  - RTP->playing markers delta <= 30s
