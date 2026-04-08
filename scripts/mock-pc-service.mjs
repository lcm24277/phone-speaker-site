import http from "node:http";
import dgram from "node:dgram";

const HTTP_PORT = 18730;
const RTP_PORT = 18732;
const RTP_PT = 111;

let targetIp = null;
let seq = 1;
let ts = 0;
const ssrc = 0x22334455;

const status = {
  connectionStatus: "idle",
  deviceName: "",
  deviceIp: "",
  networkType: "ethernet",
  latencyMs: 120,
  volumePercent: 75,
  isMuted: false,
  isLocalMonitorEnabled: false,
  virtualAudioDeviceName: "MOCK"
};

const udp = dgram.createSocket("udp4");

function buildRtp(payload) {
  const h = Buffer.alloc(12);
  h[0] = 0x80;
  h[1] = RTP_PT;
  h.writeUInt16BE(seq & 0xffff, 2);
  h.writeUInt32BE(ts >>> 0, 4);
  h.writeUInt32BE(ssrc >>> 0, 8);
  seq = (seq + 1) & 0xffff;
  ts = (ts + 960) >>> 0;
  return Buffer.concat([h, payload]);
}

setInterval(() => {
  if (!targetIp) return;
  // Mock Opus payload bytes for transport-path debugging.
  const payload = Buffer.alloc(40, 0xaa);
  const pkt = buildRtp(payload);
  udp.send(pkt, RTP_PORT, targetIp);
}, 20);

function sendJson(res, code, body) {
  res.writeHead(code, { "content-type": "application/json" });
  res.end(JSON.stringify(body));
}

function readJson(req) {
  return new Promise((resolve) => {
    const chunks = [];
    req.on("data", (c) => chunks.push(c));
    req.on("end", () => {
      try {
        resolve(JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}"));
      } catch {
        resolve({});
      }
    });
  });
}

const server = http.createServer(async (req, res) => {
  if (req.method === "GET" && req.url === "/api/status") return sendJson(res, 200, status);
  if (req.method !== "POST") return sendJson(res, 404, { error: "not found" });

  if (req.url === "/api/connect") {
    const body = await readJson(req);
    targetIp = body.targetIp || null;
    status.connectionStatus = "connected";
    status.deviceName = `Android@${targetIp}`;
    status.deviceIp = targetIp || "";
    return sendJson(res, 204, {});
  }
  if (req.url === "/api/disconnect") {
    targetIp = null;
    status.connectionStatus = "disconnected";
    return sendJson(res, 204, {});
  }
  if (req.url === "/api/audio/volume/increase") {
    status.volumePercent = Math.min(100, status.volumePercent + 5);
    return sendJson(res, 204, {});
  }
  if (req.url === "/api/audio/volume/decrease") {
    status.volumePercent = Math.max(0, status.volumePercent - 5);
    return sendJson(res, 204, {});
  }
  if (req.url === "/api/audio/mute/toggle") {
    status.isMuted = !status.isMuted;
    return sendJson(res, 204, {});
  }
  return sendJson(res, 204, {});
});

server.listen(HTTP_PORT, "0.0.0.0", () => {
  console.log(`mock-pc-service on :${HTTP_PORT}`);
});
