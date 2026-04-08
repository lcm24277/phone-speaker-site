export const SERVICE_NAME = "_mobile-speaker._udp.local";

export const HTTP_PORT = 18730;
export const WS_PORT = 18731;
export const RTP_PORT = 18732;
export const ANDROID_DISCOVERY_PORT = 18733;

export const AUDIO_SPEC = {
  sampleRate: 48000,
  channels: 2,
  format: "float32",
  opusFrameMs: 20,
  opusBitrateKbps: 160,
  payloadType: 111,
  jitterBufferMs: 120
} as const;

export type ConnectionStatus =
  | "idle"
  | "scanning"
  | "connecting"
  | "connected"
  | "reconnecting"
  | "disconnected"
  | "error";

export type PlaybackStatus = "stopped" | "buffering" | "playing" | "muted" | "error";

export interface DeviceDescriptor {
  id: string;
  name: string;
  ip: string;
  status: "available" | "busy" | "offline";
  lastSeenAt: string;
}

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

export type WsEventType =
  | "status.updated"
  | "discovery.devices"
  | "connection.error"
  | "audio.level"
  | "audio.playback";

export interface WsEvent<T = unknown> {
  type: WsEventType;
  payload: T;
}
