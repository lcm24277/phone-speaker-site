use axum::{
    extract::{
        ws::{Message, WebSocket, WebSocketUpgrade},
        State,
    },
    http::StatusCode,
    response::IntoResponse,
    routing::{get, post},
    Json, Router,
};
use chrono::Utc;
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::{SampleFormat, SampleRate, StreamConfig};
use local_ip_address::local_ip;
use mdns_sd::{ServiceDaemon, ServiceEvent, ServiceInfo};
use opus::{Application, Channels, Encoder as OpusEncoder};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::fs::{create_dir_all, OpenOptions};
use std::io::Write;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, AtomicU8, Ordering};
use std::sync::Arc;
use tokio::net::UdpSocket;
use tokio::sync::{broadcast, mpsc, RwLock};
use tokio::time::{sleep, Duration};
use tower_http::cors::CorsLayer;
use uuid::Uuid;

const HTTP_ADDR: &str = "0.0.0.0:18730";
const WS_ADDR: &str = "0.0.0.0:18731";
const RTP_PORT: u16 = 18732;
const DISCOVERY_BEACON_PORT: u16 = 18733;
const SERVICE_TYPE: &str = "_mobile-speaker._udp.local.";
const AUDIO_SAMPLE_RATE: u32 = 48_000;
const AUDIO_CHANNELS: usize = 2;
const OPUS_FRAME_SAMPLES_PER_CH: usize = 960; // 20ms @ 48k
const OPUS_FRAME_TOTAL_SAMPLES: usize = OPUS_FRAME_SAMPLES_PER_CH * AUDIO_CHANNELS;
const RTP_PAYLOAD_TYPE: u8 = 111;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct StatusResponse {
    connection_status: String,
    device_name: String,
    device_ip: String,
    network_type: String,
    latency_ms: u32,
    volume_percent: u8,
    is_muted: bool,
    is_local_monitor_enabled: bool,
    virtual_audio_device_name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct DeviceDescriptor {
    id: String,
    name: String,
    ip: String,
    status: String,
    last_seen_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
struct WsEvent<T> {
    r#type: String,
    payload: T,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct SaveSettingsPayload {
    is_start_on_boot_enabled: bool,
    is_minimize_to_tray_enabled: bool,
    is_notification_enabled: bool,
    is_local_monitor_enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct ConnectPayload {
    target_ip: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AppStateData {
    status: StatusResponse,
    devices: Vec<DeviceDescriptor>,
    settings: SaveSettingsPayload,
}

impl Default for AppStateData {
    fn default() -> Self {
        let virtual_audio = detect_vb_cable_name().unwrap_or_else(|| {
            "VB-CABLE 安装失败，或系统未检测到对应虚拟音频设备，请重新安装后重试。".to_string()
        });
        Self {
            status: StatusResponse {
                connection_status: "idle".into(),
                device_name: "".into(),
                device_ip: "".into(),
                network_type: detect_network_type(),
                latency_ms: 0,
                volume_percent: 75,
                is_muted: false,
                is_local_monitor_enabled: true,
                virtual_audio_device_name: virtual_audio,
            },
            devices: vec![],
            settings: SaveSettingsPayload {
                is_start_on_boot_enabled: false,
                is_minimize_to_tray_enabled: true,
                is_notification_enabled: true,
                is_local_monitor_enabled: true,
            },
        }
    }
}

#[derive(Clone)]
struct SharedAudioControls {
    volume_percent: Arc<AtomicU8>,
    is_muted: Arc<AtomicBool>,
    target_peer: Arc<RwLock<Option<SocketAddr>>>,
}

#[derive(Clone)]
struct MdnsRuntime {
    daemon: Arc<ServiceDaemon>,
    discovered: Arc<RwLock<HashMap<String, DeviceDescriptor>>>,
}

#[derive(Clone)]
struct AppState {
    data: Arc<RwLock<AppStateData>>,
    tx: broadcast::Sender<String>,
    audio: SharedAudioControls,
    mdns: MdnsRuntime,
}

#[tokio::main]
async fn main() {
    let _ = write_log("PC_SERVICE_STARTUP");

    let (tx, _) = broadcast::channel::<String>(256);
    let data = Arc::new(RwLock::new(AppStateData::default()));
    let discovered = Arc::new(RwLock::new(HashMap::new()));

    let mdns_daemon = ServiceDaemon::new().expect("mdns daemon init failed");
    let mdns = MdnsRuntime {
        daemon: Arc::new(mdns_daemon),
        discovered,
    };

    let audio = SharedAudioControls {
        volume_percent: Arc::new(AtomicU8::new(75)),
        is_muted: Arc::new(AtomicBool::new(false)),
        target_peer: Arc::new(RwLock::new(None)),
    };

    let state = AppState {
        data: data.clone(),
        tx: tx.clone(),
        audio: audio.clone(),
        mdns: mdns.clone(),
    };

    register_pc_mdns(&state).await;
    spawn_mdns_discovery_loop(state.clone());
    spawn_pc_discovery_beacon(state.clone());
    spawn_audio_pipeline(state.clone());

    let api_router = Router::new()
        .route("/api/status", get(get_status))
        .route("/api/discovery/start", post(start_discovery))
        .route("/api/discovery/stop", post(stop_discovery))
        .route("/api/connect", post(connect_device))
        .route("/api/disconnect", post(disconnect_device))
        .route("/api/audio/volume/increase", post(volume_increase))
        .route("/api/audio/volume/decrease", post(volume_decrease))
        .route("/api/audio/mute/toggle", post(toggle_mute))
        .route("/api/audio/local-monitor/toggle", post(toggle_local_monitor))
        .route("/api/settings/save", post(save_settings))
        .with_state(state.clone())
        .layer(CorsLayer::permissive());

    let ws_router = Router::new()
        .route("/ws", get(ws_handler))
        .with_state(state.clone())
        .layer(CorsLayer::permissive());

    let api_addr: SocketAddr = HTTP_ADDR.parse().expect("invalid HTTP addr");
    let ws_addr: SocketAddr = WS_ADDR.parse().expect("invalid WS addr");

    let api_server = axum::serve(
        tokio::net::TcpListener::bind(api_addr).await.expect("bind http"),
        api_router,
    );
    let ws_server = axum::serve(
        tokio::net::TcpListener::bind(ws_addr).await.expect("bind ws"),
        ws_router,
    );

    println!("HTTP server running at http://{HTTP_ADDR}");
    println!("WS server running at ws://{WS_ADDR}/ws");
    let _ = write_log("PC_HTTP_WS_READY");

    tokio::select! {
        res = api_server => { if let Err(e) = res { let _ = write_log(&format!("http error: {e}")); eprintln!("http error: {e}") } }
        res = ws_server => { if let Err(e) = res { let _ = write_log(&format!("ws error: {e}")); eprintln!("ws error: {e}") } }
    }
}

async fn get_status(State(state): State<AppState>) -> impl IntoResponse {
    let data = state.data.read().await;
    Json(data.status.clone())
}

async fn start_discovery(State(state): State<AppState>) -> impl IntoResponse {
    {
        let mut data = state.data.write().await;
        data.status.connection_status = "scanning".to_string();
    }

    let devices = collect_discovered_devices(&state).await;
    {
        let mut data = state.data.write().await;
        data.devices = devices.clone();
    }
    publish_event(&state, "discovery.devices", devices).await;
    StatusCode::NO_CONTENT
}

async fn stop_discovery(State(state): State<AppState>) -> impl IntoResponse {
    let mut data = state.data.write().await;
    if data.status.connection_status == "scanning" {
        data.status.connection_status = "idle".to_string();
    }
    StatusCode::NO_CONTENT
}

async fn connect_device(
    State(state): State<AppState>,
    Json(body): Json<ConnectPayload>,
) -> impl IntoResponse {
    let ip = body.target_ip.parse::<IpAddr>();
    let ip = match ip {
        Ok(v) => v,
        Err(_) => {
            publish_event(
                &state,
                "connection.error",
                serde_json::json!({"message": "invalid targetIp"}),
            )
            .await;
            return StatusCode::BAD_REQUEST;
        }
    };

    let peer = SocketAddr::new(ip, RTP_PORT);
    {
        let mut peer_guard = state.audio.target_peer.write().await;
        *peer_guard = Some(peer);
    }

    let mut data = state.data.write().await;
    data.status.connection_status = "connected".to_string();
    data.status.device_name = format!("Android@{}", body.target_ip);
    data.status.device_ip = body.target_ip;
    data.status.network_type = detect_network_type();
    data.status.latency_ms = 120;
    let payload = data.status.clone();
    drop(data);

    let _ = write_log(&format!("PC_CONNECT_OK peer={}", peer));
    publish_event(&state, "status.updated", payload).await;
    StatusCode::NO_CONTENT
}

async fn disconnect_device(State(state): State<AppState>) -> impl IntoResponse {
    {
        let mut peer_guard = state.audio.target_peer.write().await;
        *peer_guard = None;
    }

    let mut data = state.data.write().await;
    data.status.connection_status = "disconnected".to_string();
    data.status.device_name.clear();
    data.status.device_ip.clear();
    data.status.latency_ms = 0;
    let payload = data.status.clone();
    drop(data);
    let _ = write_log("PC_DISCONNECTED");
    publish_event(&state, "status.updated", payload).await;
    StatusCode::NO_CONTENT
}

async fn volume_increase(State(state): State<AppState>) -> impl IntoResponse {
    let next = state
        .audio
        .volume_percent
        .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |v| Some(v.saturating_add(5).min(100)))
        .unwrap_or(75)
        .saturating_add(5)
        .min(100);

    {
        let mut data = state.data.write().await;
        data.status.volume_percent = next;
    }
    let payload = serde_json::json!({ "volumePercent": next });
    publish_event(&state, "audio.level", payload).await;
    StatusCode::NO_CONTENT
}

async fn volume_decrease(State(state): State<AppState>) -> impl IntoResponse {
    let next = state
        .audio
        .volume_percent
        .fetch_update(Ordering::Relaxed, Ordering::Relaxed, |v| Some(v.saturating_sub(5)))
        .unwrap_or(75)
        .saturating_sub(5);

    {
        let mut data = state.data.write().await;
        data.status.volume_percent = next;
    }
    let payload = serde_json::json!({ "volumePercent": next });
    publish_event(&state, "audio.level", payload).await;
    StatusCode::NO_CONTENT
}

async fn toggle_mute(State(state): State<AppState>) -> impl IntoResponse {
    let was = state.audio.is_muted.fetch_xor(true, Ordering::Relaxed);
    let now = !was;
    {
        let mut data = state.data.write().await;
        data.status.is_muted = now;
    }
    let payload = serde_json::json!({
        "isMuted": now,
        "playbackState": if now { "muted" } else { "playing" }
    });
    publish_event(&state, "audio.playback", payload).await;
    StatusCode::NO_CONTENT
}

async fn toggle_local_monitor(State(state): State<AppState>) -> impl IntoResponse {
    let mut data = state.data.write().await;
    data.status.is_local_monitor_enabled = !data.status.is_local_monitor_enabled;
    let payload = serde_json::json!({ "isLocalMonitorEnabled": data.status.is_local_monitor_enabled });
    drop(data);
    publish_event(&state, "status.updated", payload).await;
    StatusCode::NO_CONTENT
}

async fn save_settings(
    State(state): State<AppState>,
    Json(body): Json<SaveSettingsPayload>,
) -> impl IntoResponse {
    let mut data = state.data.write().await;
    data.settings = body.clone();
    data.status.is_local_monitor_enabled = body.is_local_monitor_enabled;
    drop(data);
    StatusCode::NO_CONTENT
}

async fn ws_handler(ws: WebSocketUpgrade, State(state): State<AppState>) -> impl IntoResponse {
    ws.on_upgrade(move |socket| handle_socket(socket, state))
}

async fn handle_socket(mut socket: WebSocket, state: AppState) {
    let mut rx = state.tx.subscribe();
    while let Ok(msg) = rx.recv().await {
        if socket.send(Message::Text(msg)).await.is_err() {
            break;
        }
    }
}

async fn publish_event<T: Serialize>(state: &AppState, event_type: &str, payload: T) {
    let msg = serde_json::to_string(&WsEvent {
        r#type: event_type.to_string(),
        payload,
    })
    .expect("serialize ws event");
    let _ = state.tx.send(msg);
}

fn spawn_audio_pipeline(state: AppState) {
    tokio::spawn(async move {
        let _ = write_log("PC_AUDIO_PIPELINE_INIT");

        let (tx_pcm, mut rx_pcm) = mpsc::unbounded_channel::<Vec<f32>>();
        if start_wasapi_capture(tx_pcm.clone()).is_err() {
            let _ = write_log("WASAPI capture unavailable, using synthetic tone source");
            spawn_synthetic_source(tx_pcm);
        }

        let mut encoder = match OpusEncoder::new(AUDIO_SAMPLE_RATE, Channels::Stereo, Application::Audio) {
            Ok(enc) => enc,
            Err(err) => {
                let _ = write_log(&format!("opus encoder init failed: {err}"));
                publish_event(
                    &state,
                    "connection.error",
                    serde_json::json!({"message": format!("opus encoder init failed: {err}")}),
                )
                .await;
                return;
            }
        };
        let _ = encoder.set_bitrate(opus::Bitrate::Bits(160_000));
        let _ = encoder.set_vbr(true);

        let socket = match UdpSocket::bind("0.0.0.0:0").await {
            Ok(s) => s,
            Err(err) => {
                let _ = write_log(&format!("udp bind failed: {err}"));
                return;
            }
        };

        let mut stash = Vec::<f32>::new();
        let ssrc = rand_ssrc();
        let mut seq: u16 = 1;
        let mut ts: u32 = 0;

        let mut first_packet_logged = false;
        while let Some(mut chunk) = rx_pcm.recv().await {
            stash.append(&mut chunk);
            while stash.len() >= OPUS_FRAME_TOTAL_SAMPLES {
                let mut frame = stash.drain(..OPUS_FRAME_TOTAL_SAMPLES).collect::<Vec<f32>>();
                apply_audio_controls(
                    &mut frame,
                    state.audio.volume_percent.load(Ordering::Relaxed),
                    state.audio.is_muted.load(Ordering::Relaxed),
                );

                let mut encoded = vec![0u8; 4000];
                let encoded_len = match encoder.encode_float(&frame, &mut encoded) {
                    Ok(sz) => sz,
                    Err(err) => {
                        let _ = write_log(&format!("opus encode failed: {err}"));
                        continue;
                    }
                };
                encoded.truncate(encoded_len);

                let peer = *state.audio.target_peer.read().await;
                if let Some(target) = peer {
                    let packet = build_rtp_packet(seq, ts, ssrc, &encoded);
                    let _ = socket.send_to(&packet, target).await;
                    if !first_packet_logged {
                        first_packet_logged = true;
                        let _ = write_log(&format!("PC_RTP_FIRST_PACKET target={} seq={}", target, seq));
                    }
                }

                seq = seq.wrapping_add(1);
                ts = ts.wrapping_add(OPUS_FRAME_SAMPLES_PER_CH as u32);
            }
        }
    });
}

fn start_wasapi_capture(tx: mpsc::UnboundedSender<Vec<f32>>) -> Result<(), String> {
    let host = cpal::host_from_id(cpal::HostId::Wasapi).unwrap_or_else(|_| cpal::default_host());
    let device = select_vb_cable_input_device(&host)
        .or_else(|| host.default_input_device())
        .ok_or_else(|| "no input device found".to_string())?;

    let name = device.name().unwrap_or_else(|_| "Unknown Device".to_string());
    let _ = write_log(&format!("PC_CAPTURE_DEVICE_SELECTED name={name}"));

    let default_cfg = device
        .default_input_config()
        .map_err(|e| format!("default input config failed: {e}"))?;
    let sample_format = default_cfg.sample_format();
    let mut stream_cfg: StreamConfig = default_cfg.config();
    stream_cfg.channels = AUDIO_CHANNELS as u16;
    stream_cfg.sample_rate = SampleRate(AUDIO_SAMPLE_RATE);

    let err_cb = |err| {
        let _ = write_log(&format!("input stream error: {err}"));
    };

    let stream = match sample_format {
        SampleFormat::F32 => device
            .build_input_stream(
                &stream_cfg,
                move |data: &[f32], _| {
                    let _ = tx.send(data.to_vec());
                },
                err_cb,
                None,
            )
            .map_err(|e| format!("build f32 stream failed: {e}"))?,
        SampleFormat::I16 => {
            let tx = tx.clone();
            device
                .build_input_stream(
                    &stream_cfg,
                    move |data: &[i16], _| {
                        let chunk = data.iter().map(|v| (*v as f32) / (i16::MAX as f32)).collect::<Vec<_>>();
                        let _ = tx.send(chunk);
                    },
                    err_cb,
                    None,
                )
                .map_err(|e| format!("build i16 stream failed: {e}"))?
        }
        SampleFormat::U16 => {
            let tx = tx.clone();
            device
                .build_input_stream(
                    &stream_cfg,
                    move |data: &[u16], _| {
                        let chunk = data
                            .iter()
                            .map(|v| ((*v as f32) / (u16::MAX as f32) - 0.5) * 2.0)
                            .collect::<Vec<_>>();
                        let _ = tx.send(chunk);
                    },
                    err_cb,
                    None,
                )
                .map_err(|e| format!("build u16 stream failed: {e}"))?
        }
        _ => return Err("unsupported input sample format".to_string()),
    };

    stream
        .play()
        .map_err(|e| format!("start input stream failed: {e}"))?;

    // cpal::Stream is not Send on all platforms; leak to keep capture alive for process lifetime.
    let _stream: &'static mut cpal::Stream = Box::leak(Box::new(stream));
    Ok(())
}

fn spawn_synthetic_source(tx: mpsc::UnboundedSender<Vec<f32>>) {
    tokio::spawn(async move {
        let mut phase = 0.0f32;
        let freq = 440.0f32;
        let step = 2.0f32 * std::f32::consts::PI * freq / AUDIO_SAMPLE_RATE as f32;
        loop {
            let mut frame = Vec::with_capacity(OPUS_FRAME_TOTAL_SAMPLES);
            for _ in 0..OPUS_FRAME_SAMPLES_PER_CH {
                let s = phase.sin() * 0.2;
                phase += step;
                if phase > 2.0 * std::f32::consts::PI {
                    phase -= 2.0 * std::f32::consts::PI;
                }
                frame.push(s);
                frame.push(s);
            }
            let _ = tx.send(frame);
            sleep(Duration::from_millis(20)).await;
        }
    });
}

fn apply_audio_controls(samples: &mut [f32], volume_percent: u8, is_muted: bool) {
    if is_muted {
        samples.fill(0.0);
        return;
    }

    // Batch 1 tuning: keep a small headroom boost, but remove the previous aggressive gain stack.
    let gain = (volume_percent as f32 / 100.0).clamp(0.0, 1.0) * 1.2;
    for v in samples.iter_mut() {
        let x = *v * gain;
        *v = x.clamp(-0.95, 0.95); // limiter always on
    }
}

fn build_rtp_packet(seq: u16, timestamp: u32, ssrc: u32, payload: &[u8]) -> Vec<u8> {
    let mut packet = Vec::with_capacity(12 + payload.len());
    packet.push(0x80); // V2
    packet.push(RTP_PAYLOAD_TYPE);
    packet.extend_from_slice(&seq.to_be_bytes());
    packet.extend_from_slice(&timestamp.to_be_bytes());
    packet.extend_from_slice(&ssrc.to_be_bytes());
    packet.extend_from_slice(payload);
    packet
}

fn rand_ssrc() -> u32 {
    let bytes = Uuid::new_v4().into_bytes();
    u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]])
}

fn select_vb_cable_input_device(host: &cpal::Host) -> Option<cpal::Device> {
    let mut best: Option<cpal::Device> = None;
    if let Ok(devices) = host.input_devices() {
        for d in devices {
            let n = d.name().unwrap_or_default().to_lowercase();
            if n.contains("cable") || n.contains("vb-audio") {
                best = Some(d);
                break;
            }
        }
    }
    best
}

fn detect_vb_cable_name() -> Option<String> {
    let host = cpal::host_from_id(cpal::HostId::Wasapi).ok()?;
    let devices = host.input_devices().ok()?;
    for d in devices {
        if let Ok(name) = d.name() {
            let lower = name.to_lowercase();
            if lower.contains("cable") || lower.contains("vb-audio") {
                return Some(name);
            }
        }
    }
    None
}

fn detect_network_type() -> String {
    if let Ok(hostname) = std::env::var("COMPUTERNAME") {
        let lower = hostname.to_lowercase();
        if lower.contains("wifi") || lower.contains("wlan") {
            return "wifi".to_string();
        }
    }
    "ethernet".to_string()
}

async fn register_pc_mdns(state: &AppState) {
    let ip = local_ip().unwrap_or(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)));
    let instance_name = format!("mobile-speaker-pc-{}", Uuid::new_v4());
    let hostname = format!("{}.local.", instance_name);
    let mut props = HashMap::<String, String>::new();
    props.insert("role".to_string(), "pc".to_string());
    props.insert("app".to_string(), "mobile-speaker".to_string());
    props.insert("version".to_string(), "0.1.0".to_string());
    props.insert("rtp_port".to_string(), "18732".to_string());
    props.insert("ws_port".to_string(), "18731".to_string());
    props.insert("http_port".to_string(), "18730".to_string());

    let service = ServiceInfo::new(SERVICE_TYPE, &instance_name, &hostname, ip, RTP_PORT, props);
    match service {
        Ok(info) => {
            if let Err(err) = state.mdns.daemon.register(info) {
            let _ = write_log(&format!("PC_MDNS_REGISTER_FAIL error={err}"));
        } else {
                let _ = write_log(&format!("PC_MDNS_REGISTERED ip={}", ip));
        }
        }
        Err(err) => {
            let _ = write_log(&format!("mdns service info build failed: {err}"));
        }
    }
}

fn spawn_mdns_discovery_loop(state: AppState) {
    tokio::spawn(async move {
        let receiver = match state.mdns.daemon.browse(SERVICE_TYPE) {
            Ok(r) => r,
            Err(err) => {
                let _ = write_log(&format!("PC_MDNS_BROWSE_FAIL error={err}"));
                return;
            }
        };

        loop {
            match receiver.recv_timeout(Duration::from_secs(1)) {
                Ok(event) => match event {
                    ServiceEvent::ServiceResolved(info) => {
                        if let Some(device) = to_android_device(info) {
                            let mut d = state.mdns.discovered.write().await;
                            d.insert(device.id.clone(), device);
                            let devices = d.values().cloned().collect::<Vec<_>>();
                            drop(d);
                            {
                                let mut data = state.data.write().await;
                                data.devices = devices.clone();
                            }
                            publish_event(&state, "discovery.devices", devices).await;
                        }
                    }
                    ServiceEvent::ServiceRemoved(_, fullname) => {
                        let mut d = state.mdns.discovered.write().await;
                        d.retain(|_, v| v.id != fullname);
                        let devices = d.values().cloned().collect::<Vec<_>>();
                        drop(d);
                        {
                            let mut data = state.data.write().await;
                            data.devices = devices.clone();
                        }
                        publish_event(&state, "discovery.devices", devices).await;
                    }
                    _ => {}
                },
                Err(_) => {}
            }
        }
    });
}

fn to_android_device(info: ServiceInfo) -> Option<DeviceDescriptor> {
    let role = info
        .get_property_val_str("role")
        .map(|v| v.to_string())
        .unwrap_or_default();
    if role != "android" {
        return None;
    }
    let ip = info.get_addresses().iter().next()?.to_string();
    let fullname = info.get_fullname().to_string();
    Some(DeviceDescriptor {
        id: fullname.clone(),
        name: info.get_hostname().to_string(),
        ip: ip.clone(),
        status: "available".to_string(),
        last_seen_at: Utc::now().to_rfc3339(),
    })
}

fn spawn_pc_discovery_beacon(_state: AppState) {
    tokio::spawn(async move {
        let socket = match std::net::UdpSocket::bind("0.0.0.0:0") {
            Ok(s) => s,
            Err(err) => {
                let _ = write_log(&format!("PC_DISCOVERY_BEACON_BIND_FAIL error={err}"));
                return;
            }
        };
        if let Err(err) = socket.set_broadcast(true) {
            let _ = write_log(&format!("PC_DISCOVERY_BEACON_BROADCAST_FAIL error={err}"));
            return;
        }
        let ip = local_ip().unwrap_or(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)));
        let name = std::env::var("COMPUTERNAME").unwrap_or_else(|_| "Mobile Speaker PC".to_string());
        let id = Uuid::new_v4().to_string();
        let payload = serde_json::json!({
            "role": "pc",
            "app": "mobile-speaker",
            "id": id,
            "name": name,
            "ip": ip.to_string(),
            "httpPort": 18730,
            "wsPort": 18731,
            "rtpPort": 18732
        })
        .to_string();
        let target = SocketAddr::new(IpAddr::V4(Ipv4Addr::new(255, 255, 255, 255)), DISCOVERY_BEACON_PORT);
        let _ = write_log(&format!("PC_DISCOVERY_BEACON_ON ip={} target={}", ip, target));
        loop {
            let _ = socket.send_to(payload.as_bytes(), target);
            sleep(Duration::from_secs(1)).await;
        }
    });
}

async fn collect_discovered_devices(state: &AppState) -> Vec<DeviceDescriptor> {
    let d = state.mdns.discovered.read().await;
    d.values().cloned().collect()
}

fn write_log(line: &str) -> Result<(), String> {
    let base = PathBuf::from("logs");
    if !base.exists() {
        create_dir_all(&base).map_err(|e| e.to_string())?;
    }
    let path = base.join("pc-audio-service.log");
    let mut f = OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
        .map_err(|e| e.to_string())?;
    let ts = Utc::now().to_rfc3339();
    writeln!(f, "[{ts}] {line}").map_err(|e| e.to_string())
}
