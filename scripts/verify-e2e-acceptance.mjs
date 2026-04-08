import fs from "node:fs";
import path from "node:path";

function arg(name, fallback = "") {
  const idx = process.argv.indexOf(name);
  return idx >= 0 && idx + 1 < process.argv.length ? process.argv[idx + 1] : fallback;
}

const pcLogPath = path.resolve(arg("--pc", "services/pc-audio-service-rust/logs/pc-audio-service.log"));
const androidLogPath = path.resolve(arg("--android", "artifacts/e2e/android-logcat.log"));
const since = arg("--since", "");
const sinceMs = since ? Date.parse(since) : NaN;

if (!fs.existsSync(pcLogPath)) {
  console.error(`Missing PC log: ${pcLogPath}`);
  process.exit(1);
}
if (!fs.existsSync(androidLogPath)) {
  console.error(`Missing Android log: ${androidLogPath}`);
  process.exit(1);
}

let pcText = fs.readFileSync(pcLogPath, "utf8");
let androidText = fs.readFileSync(androidLogPath, "utf8");

if (!Number.isNaN(sinceMs)) {
  pcText = pcText
    .split(/\r?\n/)
    .filter((line) => {
      const ts = line.match(/\[([^\]]+)\]/)?.[1];
      if (!ts) return false;
      const ms = Date.parse(ts);
      return !Number.isNaN(ms) && ms >= sinceMs;
    })
    .join("\n");

  androidText = androidText
    .split(/\r?\n/)
    .filter((line) => {
      const iso = line.match(/(20\d\d-[^| ]+)/)?.[1];
      if (!iso) return false;
      const ms = Date.parse(iso);
      return !Number.isNaN(ms) && ms >= sinceMs;
    })
    .join("\n");
}

const requiredPcMarkers = ["PC_MDNS_REGISTERED", "PC_CONNECT_OK", "PC_RTP_FIRST_PACKET"];
const requiredAndroidMarkers = ["ANDROID_CONNECT_OK", "ANDROID_RTP_PACKET", "ANDROID_PLAYBACK_PLAYING"];

const missingPc = requiredPcMarkers.filter((m) => !pcText.includes(m));
const missingAndroid = requiredAndroidMarkers.filter((m) => !androidText.includes(m));
if (missingPc.length || missingAndroid.length) {
  if (missingPc.length) console.error(`Missing PC markers: ${missingPc.join(", ")}`);
  if (missingAndroid.length) console.error(`Missing Android markers: ${missingAndroid.join(", ")}`);
  process.exit(1);
}

function extractIsoTimestamp(logText, marker) {
  const line = logText.split(/\r?\n/).find((l) => l.includes(marker));
  if (!line) return null;
  // PC line format: [2026-...Z] MARKER...
  const bracket = line.match(/\[([^\]]+)\]/)?.[1];
  if (bracket) return Date.parse(bracket);
  // Android line includes "... 2026-...Z | MARKER"
  const iso = line.match(/(20\d\d-[^| ]+)/)?.[1];
  if (!iso) return null;
  return Date.parse(iso);
}

const pcConnectTs = extractIsoTimestamp(pcText, "PC_CONNECT_OK");
const androidConnectTs = extractIsoTimestamp(androidText, "ANDROID_CONNECT_OK");
const pcRtpTs = extractIsoTimestamp(pcText, "PC_RTP_FIRST_PACKET");
const androidPlayTs = extractIsoTimestamp(androidText, "ANDROID_PLAYBACK_PLAYING");

function assertWindow(label, a, b, maxMs) {
  if (!a || !b) {
    throw new Error(`${label} timestamp missing`);
  }
  const delta = Math.abs(a - b);
  if (delta > maxMs) {
    throw new Error(`${label} time delta too large: ${delta}ms > ${maxMs}ms`);
  }
  return delta;
}

let connectDelta = 0;
let playbackDelta = 0;
try {
  connectDelta = assertWindow("connect alignment", pcConnectTs, androidConnectTs, 20_000);
  playbackDelta = assertWindow("playback alignment", pcRtpTs, androidPlayTs, 30_000);
} catch (err) {
  console.error(`Acceptance FAIL: ${err.message}`);
  process.exit(1);
}

const summary = {
  result: "PASS",
  pcLogPath,
  androidLogPath,
  checks: {
    requiredPcMarkers,
    requiredAndroidMarkers,
    since: since || null,
    connectAlignmentMs: connectDelta,
    playbackAlignmentMs: playbackDelta
  }
};

console.log(JSON.stringify(summary, null, 2));
