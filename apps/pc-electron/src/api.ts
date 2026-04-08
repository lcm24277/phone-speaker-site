export type ConnectionStatus =
  | "idle"
  | "scanning"
  | "connecting"
  | "connected"
  | "reconnecting"
  | "disconnected"
  | "error";

export interface StatusResponse {
  connectionStatus: ConnectionStatus;
  deviceName: string;
  deviceIp: string;
  networkType: "ethernet" | "wifi" | "unknown";
  latencyMs: number;
  volumePercent: number;
  isMuted: boolean;
  isLocalMonitorEnabled: boolean;
  virtualAudioDeviceName: string;
}

export interface DeviceDescriptor {
  id: string;
  name: string;
  ip: string;
  status: string;
  lastSeenAt: string;
}

const API_BASE = "http://127.0.0.1:18730";
const WS_URL = "ws://127.0.0.1:18731/ws";

export async function getStatus(): Promise<StatusResponse> {
  const res = await fetch(`${API_BASE}/api/status`);
  if (!res.ok) throw new Error("status request failed");
  return res.json();
}

export async function post(path: string, body?: unknown): Promise<void> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: body ? JSON.stringify(body) : undefined
  });
  if (!res.ok) throw new Error(`request failed: ${path}`);
}

export function connectWs(onMessage: (event: { type: string; payload: unknown }) => void): WebSocket {
  const ws = new WebSocket(WS_URL);
  ws.onmessage = (msg) => {
    try {
      onMessage(JSON.parse(msg.data as string));
    } catch {
      // ignore malformed ws payload
    }
  };
  return ws;
}
