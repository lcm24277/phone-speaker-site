import { spawn } from "node:child_process";
import dgram from "node:dgram";

const HTTP = "http://127.0.0.1:18730";
const WS = "ws://127.0.0.1:18731/ws";
const RTP_PORT = 18732;

function sleep(ms) {
  return new Promise((r) => setTimeout(r, ms));
}

async function waitForHttpReady(retries = 80) {
  for (let i = 0; i < retries; i += 1) {
    try {
      const res = await fetch(`${HTTP}/api/status`);
      if (res.ok) return;
    } catch {
      // ignore
    }
    await sleep(250);
  }
  throw new Error("Rust service HTTP not ready");
}

async function post(path, body) {
  const res = await fetch(`${HTTP}${path}`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: body ? JSON.stringify(body) : undefined
  });
  if (!res.ok) throw new Error(`POST ${path} failed: ${res.status}`);
}

function verifyRtp(packet) {
  if (!packet || packet.length <= 12) return "RTP packet too short";
  const version = packet[0] >> 6;
  const payloadType = packet[1] & 0x7f;
  if (version !== 2) return `Invalid RTP version: ${version}`;
  if (payloadType !== 111) return `Invalid payload type: ${payloadType}`;
  return null;
}

async function main() {
  const serviceCwd = "C:\\Program_Data\\Codex\\mobile-speaker\\services\\pc-audio-service-rust";
  const rust = spawn("cargo", ["run"], {
    cwd: serviceCwd,
    stdio: ["ignore", "pipe", "pipe"],
    shell: true
  });

  rust.stdout.on("data", (d) => process.stdout.write(`[rust] ${d}`));
  rust.stderr.on("data", (d) => process.stderr.write(`[rust] ${d}`));

  const wsEvents = [];
  let ws;
  const udp = dgram.createSocket("udp4");

  try {
    await waitForHttpReady();
    ws = new WebSocket(WS);
    ws.onmessage = (evt) => wsEvents.push(String(evt.data));

    await new Promise((resolve, reject) => {
      udp.once("error", reject);
      udp.bind(RTP_PORT, "127.0.0.1", resolve);
    });

    await post("/api/discovery/start");
    await post("/api/connect", { targetIp: "127.0.0.1" });

    const packet = await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error("No RTP packet received within timeout")), 12000);
      udp.once("message", (msg) => {
        clearTimeout(timeout);
        resolve(msg);
      });
    });

    const rtpError = verifyRtp(packet);
    if (rtpError) throw new Error(rtpError);

    await post("/api/audio/volume/increase");
    await post("/api/audio/mute/toggle");
    await post("/api/audio/mute/toggle");
    await post("/api/disconnect");

    console.log("E2E PASS");
    console.log(`Captured RTP bytes: ${packet.length}`);
    console.log(`WS events observed: ${wsEvents.length}`);
  } finally {
    try {
      if (ws) ws.close();
    } catch {}
    try {
      udp.close();
    } catch {}
    rust.kill("SIGTERM");
  }
}

main().catch((err) => {
  console.error(`E2E FAIL: ${err.message}`);
  process.exit(1);
});
