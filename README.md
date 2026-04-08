# mobile-speaker

V4 MVP monorepo scaffold for "手机变音箱".

## Structure

- `apps/pc-electron`: Electron + React + TypeScript + Vite UI
- `apps/android-app`: Kotlin + Jetpack Compose skeleton
- `services/pc-audio-service-rust`: Rust + Tokio + Axum backend (HTTP + WS)
- `packages/shared-protocol`: fixed ports/events/state contracts
- `packages/shared-design-tokens`: shared color and spacing tokens
- `integrations/virtual-audio-driver/vb-cable`: VB-CABLE integration placeholders
- `build/windows-installer`: NSIS/Electron Builder helper assets
- `build/android`: Android release build notes
- `docs`: setup and protocol docs

## Fixed V4 Constants

- mDNS service: `_mobile-speaker._udp.local`
- HTTP: `127.0.0.1:18730`
- WebSocket: `127.0.0.1:18731`
- RTP/UDP: `18732`
- Android discovery listen: `18733`

Audio:
- 48kHz, 2ch, float32
- Opus 20ms, 160kbps VBR
- RTP PT 111
- Android jitter buffer init 120ms

## Self Test (current machine)

This workspace includes runnable Node-based contract checks:

```powershell
node .\scripts\self-test.mjs
```

Rust/Android compilation requires local `cargo` and `java/gradle` toolchains.
