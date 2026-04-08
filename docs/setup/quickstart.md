# Quickstart

## 1) Self-test

```powershell
cd C:\Program_Data\Codex\mobile-speaker
node .\scripts\self-test.mjs
```

## 2) Run Rust service (requires Rust toolchain)

```powershell
cd C:\Program_Data\Codex\mobile-speaker\services\pc-audio-service-rust
cargo run
```

## 3) Run PC UI (requires Node dependencies)

```powershell
cd C:\Program_Data\Codex\mobile-speaker\apps\pc-electron
npm.cmd install
npm.cmd run dev
```

## 4) Run PC E2E link test (requires Rust toolchain + cargo in PATH)

```powershell
cd C:\Program_Data\Codex\mobile-speaker
node .\scripts\pc-e2e.mjs
```

## 5) Run Android static integration check

```powershell
cd C:\Program_Data\Codex\mobile-speaker
node .\scripts\android-static-check.mjs
```

## 6) Run real-device E2E + acceptance automation

```powershell
cd C:\Program_Data\Codex\mobile-speaker
powershell -ExecutionPolicy Bypass -File .\scripts\run-real-device-e2e.ps1 -PcIp <PC_LAN_IP>
```
