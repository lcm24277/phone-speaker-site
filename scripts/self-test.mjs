import fs from "node:fs";
import path from "node:path";

const root = path.resolve(process.cwd());

const requiredFiles = [
  "packages/shared-protocol/src/index.ts",
  "services/pc-audio-service-rust/src/main.rs",
  "apps/pc-electron/src/App.tsx",
  "apps/android-app/app/src/main/java/com/mobilespeaker/app/MainActivity.kt",
  "integrations/virtual-audio-driver/vb-cable/scripts/install-vb-cable.ps1",
  "integrations/virtual-audio-driver/vb-cable/detect/detect-vb-cable.ps1",
  "scripts/pc-e2e.mjs",
  "scripts/run-real-device-e2e.ps1",
  "scripts/verify-e2e-acceptance.mjs",
  "apps/android-app/app/src/main/java/com/mobilespeaker/app/util/AppLogger.kt",
  "apps/android-app/app/src/main/java/com/mobilespeaker/app/network/AndroidPresenceAdvertiser.kt"
];

const missing = requiredFiles.filter((f) => !fs.existsSync(path.join(root, f)));
if (missing.length > 0) {
  console.error("Missing required files:");
  for (const f of missing) console.error(`- ${f}`);
  process.exit(1);
}

const protocol = fs.readFileSync(path.join(root, "packages/shared-protocol/src/index.ts"), "utf8");
const requiredSnippets = [
  "HTTP_PORT = 18730",
  "WS_PORT = 18731",
  "RTP_PORT = 18732",
  "ANDROID_DISCOVERY_PORT = 18733",
  "_mobile-speaker._udp.local",
  "payloadType: 111",
  "jitterBufferMs: 120"
];

const protocolMiss = requiredSnippets.filter((s) => !protocol.includes(s));
if (protocolMiss.length > 0) {
  console.error("Protocol constants mismatch:");
  for (const s of protocolMiss) console.error(`- ${s}`);
  process.exit(1);
}

const rustMain = fs.readFileSync(path.join(root, "services/pc-audio-service-rust/src/main.rs"), "utf8");
const rustMarkers = [
  "mdns_sd",
  "OpusEncoder",
  "cpal::host_from_id",
  "build_rtp_packet",
  "RTP_PAYLOAD_TYPE: u8 = 111"
];
const rustMiss = rustMarkers.filter((s) => !rustMain.includes(s));
if (rustMiss.length > 0) {
  console.error("Rust real pipeline markers missing:");
  for (const s of rustMiss) console.error(`- ${s}`);
  process.exit(1);
}

console.log("Self-test passed: project scaffold and V4 fixed constants are in place.");
