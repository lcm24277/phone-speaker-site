import fs from "node:fs";
import path from "node:path";

const root = path.resolve(process.cwd());
const base = path.join(
  root,
  "apps/android-app/app/src/main/java/com/mobilespeaker/app"
);

const files = [
  "network/PcDiscoveryManager.kt",
  "network/AndroidPresenceAdvertiser.kt",
  "network/PcApiClient.kt",
  "audio/RtpReceiver.kt",
  "audio/AudioPlaybackEngine.kt",
  "audio/JitterBuffer.kt",
  "stream/StreamCoordinator.kt",
  "service/PlaybackForegroundService.kt",
  "util/AppLogger.kt"
];

for (const rel of files) {
  const p = path.join(base, rel);
  if (!fs.existsSync(p)) {
    console.error(`Missing Android round-3 file: ${rel}`);
    process.exit(1);
  }
}

const markers = [
  ["network/PcDiscoveryManager.kt", "NsdManager"],
  ["network/AndroidPresenceAdvertiser.kt", "registerService"],
  ["network/AndroidPresenceAdvertiser.kt", "role\", \"android"],
  ["audio/RtpReceiver.kt", "DatagramSocket"],
  ["audio/AudioPlaybackEngine.kt", "com.theeasiestway.opus.Opus"],
  ["audio/AudioPlaybackEngine.kt", "AudioTrack.MODE_STREAM"],
  ["audio/JitterBuffer.kt", "warmupFrames"],
  ["stream/StreamCoordinator.kt", "/api/connect"],
  ["stream/StreamCoordinator.kt", "ANDROID_PLAYBACK_PLAYING"],
  ["MainActivity.kt", "auto_connect_ip"],
  ["util/AppLogger.kt", "MobileSpeakerAndroid"]
];

for (const [rel, marker] of markers) {
  const p = path.join(base, rel);
  const content = fs.readFileSync(p, "utf8");
  if (!content.includes(marker)) {
    console.error(`Missing marker "${marker}" in ${rel}`);
    process.exit(1);
  }
}

console.log("Android static check passed.");
