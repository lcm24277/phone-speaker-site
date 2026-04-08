import { useEffect, useMemo, useState } from "react";
import { connectWs, DeviceDescriptor, getStatus, post, StatusResponse } from "./api";

type Page = "home" | "devices" | "settings" | "help" | "feedback";
type Language = "zh-CN" | "en-US";

type SettingsState = {
  isStartOnBootEnabled: boolean;
  isMinimizeToTrayEnabled: boolean;
  isNotificationEnabled: boolean;
  isLocalMonitorEnabled: boolean;
  language: Language;
};

type LogEntry = {
  at: string;
  kind: string;
  detail?: string;
};

type Copy = {
  appTitle: string;
  appSubtitle: string;
  menuLabel: string;
  statusLabel: string;
  home: string;
  devices: string;
  settings: string;
  help: string;
  connectedPhone: string;
  waitingForDevice: string;
  streamingNow: string;
  connectGuideHint: string;
  deviceName: string;
  deviceIp: string;
  network: string;
  volume: string;
  volumeUp: string;
  volumeDown: string;
  mute: string;
  unmute: string;
  disconnect: string;
  reconnect: string;
  audioQuality: string;
  mutedState: string;
  highState: string;
  latency: string;
  logsTitle: string;
  noEvents: string;
  scanDevices: string;
  scanHint: string;
  startScan: string;
  scanStatus: string;
  scanning: string;
  idle: string;
  manualIpPlaceholder: string;
  manualConnect: string;
  stopScan: string;
  availableDevices: string;
  noDevices: string;
  connect: string;
  availableDevice: string;
  softwareSettings: string;
  currentVirtualDevice: string;
  languageLabel: string;
  languageHelp: string;
  chinese: string;
  english: string;
  startOnBoot: string;
  startOnBootHelp: string;
  minimizeToTray: string;
  minimizeToTrayHelp: string;
  systemNotification: string;
  systemNotificationHelp: string;
  localMonitor: string;
  localMonitorHelp: string;
  saveSettings: string;
  resetDefaults: string;
  firstUseGuide: string;
  guideHint: string;
  viewTutorial: string;
  welcomeTitle: string;
  close: string;
  step1Title: string;
  step1Desc: string;
  step2Title: string;
  step2Desc: string;
  step3Title: string;
  step3Desc: string;
  step4Title: string;
  step4Desc: string;
  reconnectMissingTarget: string;
  reconnectingTo: string;
  manualConnectReady: string;
  settingsSaved: string;
  localMonitorEnabled: string;
  localMonitorDisabled: string;
  statusIdle: string;
  statusScanning: string;
  statusConnecting: string;
  statusConnected: string;
  statusReconnecting: string;
  statusDisconnected: string;
  statusError: string;
  networkWifi: string;
  networkEthernet: string;
  networkUnknown: string;
  phoneNameFallback: string;
};

const copyMap: Record<Language, Copy> = {
  "zh-CN": {
    appTitle: "手机变音箱",
    appSubtitle: "Windows 控制台",
    menuLabel: "菜单",
    statusLabel: "状态",
    home: "首页",
    devices: "设备连接",
    settings: "软件设置",
    help: "帮助",
    connectedPhone: "已连接 Android 手机",
    waitingForDevice: "等待设备连接",
    streamingNow: "音频正在实时传输到手机",
    connectGuideHint: "请先前往设备页扫描可用手机设备",
    deviceName: "设备名称",
    deviceIp: "设备 IP",
    network: "网络",
    volume: "音量",
    volumeUp: "音量+",
    volumeDown: "音量-",
    mute: "静音",
    unmute: "取消静音",
    disconnect: "断开连接",
    reconnect: "重新连接",
    audioQuality: "音频质量",
    mutedState: "已静音",
    highState: "高",
    latency: "延时",
    logsTitle: "运行日志",
    noEvents: "暂无事件",
    scanDevices: "扫描设备",
    scanHint: "扫描当前局域网中可用的 Android 设备。",
    startScan: "开始扫描",
    scanStatus: "扫描状态",
    scanning: "扫描中",
    idle: "空闲",
    manualIpPlaceholder: "手动输入设备 IP",
    manualConnect: "手动连接",
    stopScan: "停止扫描",
    availableDevices: "发现记录",
    noDevices: "暂未发现设备，请先开始扫描。",
    connect: "连接",
    availableDevice: "可用",
    softwareSettings: "软件设置",
    currentVirtualDevice: "当前虚拟音频设备：",
    languageLabel: "语言",
    languageHelp: "切换桌面端界面显示语言",
    chinese: "中文",
    english: "English",
    startOnBoot: "开机启动",
    startOnBootHelp: "Windows 登录后自动启动 PC 服务",
    minimizeToTray: "最小化到托盘",
    minimizeToTrayHelp: "关闭窗口时继续在后台运行",
    systemNotification: "系统通知",
    systemNotificationHelp: "连接、断开或报错时显示桌面通知",
    localMonitor: "本地监听",
    localMonitorHelp: "电脑端同时监听播放当前音频",
    saveSettings: "保存设置",
    resetDefaults: "恢复默认",
    firstUseGuide: "首次使用指引",
    guideHint: "按照以下步骤完成电脑和手机的首次连接。",
    viewTutorial: "查看教程",
    welcomeTitle: "欢迎使用手机变音箱",
    close: "关闭",
    step1Title: "安装 Android 应用",
    step1Desc: "在安卓手机上安装并打开手机变音箱应用。",
    step2Title: "连接同一 Wi-Fi",
    step2Desc: "确保电脑和手机连接到同一个局域网。",
    step3Title: "扫描设备",
    step3Desc: "在设备连接页扫描，等待发现手机端设备记录。",
    step4Title: "切换音频输出",
    step4Desc: "将系统或目标应用的输出切换到 VB-CABLE Input。",
    reconnectMissingTarget: "当前没有可用于重新连接的设备 IP，请先扫描设备。",
    reconnectingTo: "正在连接到",
    manualConnectReady: "目标 IP 已就绪：",
    settingsSaved: "软件设置已保存。",
    localMonitorEnabled: "已开启本地监听。",
    localMonitorDisabled: "已关闭本地监听。",
    statusIdle: "空闲",
    statusScanning: "扫描中",
    statusConnecting: "连接中",
    statusConnected: "已连接",
    statusReconnecting: "重新连接中",
    statusDisconnected: "已断开",
    statusError: "错误",
    networkWifi: "WiFi",
    networkEthernet: "有线网络",
    networkUnknown: "未知",
    phoneNameFallback: "Android 手机"
  },
  "en-US": {
    appTitle: "Mobile Speaker",
    appSubtitle: "Windows Console",
    menuLabel: "MENU",
    statusLabel: "STATUS",
    home: "Home",
    devices: "Devices",
    settings: "Software",
    help: "Help",
    connectedPhone: "Connected to Android phone",
    waitingForDevice: "Waiting for device",
    streamingNow: "Audio is streaming in real time",
    connectGuideHint: "Go to the device page to scan and connect first",
    deviceName: "Device Name",
    deviceIp: "Device IP",
    network: "Network",
    volume: "Volume",
    volumeUp: "Volume+",
    volumeDown: "Volume-",
    mute: "Mute",
    unmute: "Unmute",
    disconnect: "Disconnect",
    reconnect: "Reconnect",
    audioQuality: "Audio Quality",
    mutedState: "Muted",
    highState: "High",
    latency: "Latency",
    logsTitle: "Runtime Logs",
    noEvents: "No events yet",
    scanDevices: "Scan Devices",
    scanHint: "Search for available Android devices on the local network.",
    startScan: "Start Scan",
    scanStatus: "Scan Status",
    scanning: "Scanning",
    idle: "Idle",
    manualIpPlaceholder: "Enter device IP manually, for example 192.168.1.150",
    manualConnect: "Manual Connect",
    stopScan: "Stop Scan",
    availableDevices: "Available Devices",
    noDevices: "No devices found yet. Start scanning first.",
    connect: "Connect",
    availableDevice: "Available",
    softwareSettings: "Software Settings",
    currentVirtualDevice: "Current virtual audio device:",
    languageLabel: "Language",
    languageHelp: "Switch the display language of the desktop app",
    chinese: "Chinese",
    english: "English",
    startOnBoot: "Start on boot",
    startOnBootHelp: "Launch the PC service automatically after Windows sign-in",
    minimizeToTray: "Minimize to tray",
    minimizeToTrayHelp: "Keep running in the background when the window closes",
    systemNotification: "System notifications",
    systemNotificationHelp: "Show desktop notifications on connect, disconnect, or errors",
    localMonitor: "Local monitor",
    localMonitorHelp: "Also monitor audio on this PC",
    saveSettings: "Save Settings",
    resetDefaults: "Reset Defaults",
    firstUseGuide: "First-time Setup Guide",
    guideHint: "Follow these steps to connect your PC and phone.",
    viewTutorial: "View Tutorial",
    welcomeTitle: "Welcome to Mobile Speaker",
    close: "Close",
    step1Title: "Install the Android app",
    step1Desc: "Install and open the Mobile Speaker app on your Android phone.",
    step2Title: "Join the same Wi-Fi",
    step2Desc: "Make sure your PC and phone are on the same local network.",
    step3Title: "Scan and connect",
    step3Desc: "Open the devices page, scan, then connect to the target phone.",
    step4Title: "Switch audio output",
    step4Desc: "Route the target app or system output to VB-CABLE Input.",
    reconnectMissingTarget: "No device IP is available for reconnect. Scan devices or enter an IP first.",
    reconnectingTo: "Reconnecting to",
    manualConnectReady: "Target IP ready:",
    settingsSaved: "Software settings saved.",
    localMonitorEnabled: "Local monitor enabled.",
    localMonitorDisabled: "Local monitor disabled.",
    statusIdle: "Idle",
    statusScanning: "Scanning",
    statusConnecting: "Connecting",
    statusConnected: "Connected",
    statusReconnecting: "Reconnecting",
    statusDisconnected: "Disconnected",
    statusError: "Error",
    networkWifi: "Wi-Fi",
    networkEthernet: "Ethernet",
    networkUnknown: "Unknown",
    phoneNameFallback: "Android Phone"
  }
};

const defaultStatus: StatusResponse = {
  connectionStatus: "idle",
  deviceName: "",
  deviceIp: "",
  networkType: "unknown",
  latencyMs: 0,
  volumePercent: 75,
  isMuted: false,
  isLocalMonitorEnabled: true,
  virtualAudioDeviceName: "CABLE Input (VB-Audio Virtual Cable)"
};

const defaultSettings = (language: Language): SettingsState => ({
  isStartOnBootEnabled: false,
  isMinimizeToTrayEnabled: true,
  isNotificationEnabled: true,
  isLocalMonitorEnabled: true,
  language
});

function readStoredLanguage(): Language {
  const saved = window.localStorage.getItem("pc-ui-language");
  if (saved === "zh-CN" || saved === "en-US") {
    return saved;
  }

  return getSystemLanguage();
}

function readStoredTargetIp(): string {
  return window.localStorage.getItem("pc-last-target-ip") ?? "";
}

function getSystemLanguage(): Language {
  const systemLanguage = navigator.language || "";
  return systemLanguage.toLowerCase().startsWith("zh") ? "zh-CN" : "en-US";
}

export function App() {
  const [page, setPage] = useState<Page>("home");
  const [status, setStatus] = useState<StatusResponse>(defaultStatus);
  const [devices, setDevices] = useState<DeviceDescriptor[]>([]);
  const [scanStatus, setScanStatus] = useState<"idle" | "scanning">("idle");
  const [lastTargetIp, setLastTargetIp] = useState(readStoredTargetIp);
  const [settings, setSettings] = useState<SettingsState>(() => defaultSettings(readStoredLanguage()));
  const [logs, setLogs] = useState<LogEntry[]>([]);
  const [showGuide, setShowGuide] = useState(false);

  const t = copyMap[settings.language];

  useEffect(() => {
    window.localStorage.setItem("pc-ui-language", settings.language);
  }, [settings.language]);

  useEffect(() => {
    if (lastTargetIp) {
      window.localStorage.setItem("pc-last-target-ip", lastTargetIp);
    }
  }, [lastTargetIp]);

  useEffect(() => {
    getStatus()
      .then((next) => {
        setStatus(next);
        if (next.deviceIp) {
          setLastTargetIp(next.deviceIp);
        }
      })
      .catch(() => undefined);

    const ws = connectWs((evt) => {
      setLogs((prev) => [{ at: new Date().toLocaleTimeString(), kind: evt.type }, ...prev].slice(0, 16));

      if (evt.type === "status.updated") {
        setStatus((prev) => {
          const merged = { ...prev, ...(evt.payload as Partial<StatusResponse>) };
          if (merged.deviceIp) {
            setLastTargetIp(merged.deviceIp);
          }
          return merged;
        });
      }

      if (evt.type === "discovery.devices") {
        setDevices(evt.payload as DeviceDescriptor[]);
        setScanStatus("idle");
      }
    });

    return () => ws.close();
  }, []);

  const appendLog = (kind: string, detail?: string) => {
    setLogs((prev) => [{ at: new Date().toLocaleTimeString(), kind, detail }, ...prev].slice(0, 16));
  };

  const refreshStatus = async () => {
    const latest = await getStatus();
    setStatus(latest);
    if (latest.deviceIp) {
      setLastTargetIp(latest.deviceIp);
    }
    return latest;
  };

  const run = async (path: string, body?: unknown) => {
    await post(path, body);
    await refreshStatus();
  };

  const connectToIp = async (ip: string) => {
    const targetIp = ip.trim();
    if (!targetIp) {
      appendLog("reconnect.missing");
      return;
    }

    setLastTargetIp(targetIp);
    appendLog("connect.target", targetIp);
    setStatus((prev) => ({ ...prev, connectionStatus: "connecting" }));
    await run("/api/connect", { targetIp });
  };

  const handleReconnect = async () => {
    const targetIp =
      status.deviceIp ||
      lastTargetIp ||
      devices.find((device) => device.ip === lastTargetIp)?.ip ||
      devices[0]?.ip ||
      "";
    if (!targetIp) {
      appendLog("reconnect.missing");
      return;
    }

    setStatus((prev) => ({ ...prev, connectionStatus: "reconnecting" }));
    await connectToIp(targetIp);
  };

  const statusTone =
    status.connectionStatus === "connected"
      ? "connected"
      : status.connectionStatus === "connecting" || status.connectionStatus === "reconnecting"
        ? "connecting"
        : "disconnected";

  const stats = useMemo(
    () => [
      { label: t.audioQuality, value: status.isMuted ? t.mutedState : t.highState },
      { label: t.latency, value: `${status.latencyMs}ms` },
      { label: t.network, value: toNetworkText(status.networkType, t) }
    ],
    [status.isMuted, status.latencyMs, status.networkType, t]
  );

  return (
    <div className="window-shell">
      <header className="window-titlebar">
        <div className="titlebrand">
          <div className="titlebrand-mark">M</div>
          <div className="titlebrand-copy">
            <strong>{t.appTitle}</strong>
            <span>{t.appSubtitle}</span>
          </div>
        </div>
      </header>

      <div className="window-body">
        <aside className="sidebar">
          <div className="sidebar-section">
            <div className="sidebar-label">{t.menuLabel}</div>
            <NavButton page={page} target="home" label={t.home} onClick={setPage} />
            <NavButton page={page} target="devices" label={t.devices} onClick={setPage} />
            <NavButton page={page} target="settings" label={t.settings} onClick={setPage} />
            <NavButton page={page} target="help" label={t.help} onClick={setPage} />
            <NavButton
              page={page}
              target="feedback"
              label={settings.language === "en-US" ? "Feedback" : "使用反馈"}
              onClick={setPage}
            />
          </div>

          <div className="sidebar-section sidebar-status-panel">
            <div className="sidebar-label">{t.statusLabel}</div>
            <div className="sidebar-status-card">
              <span className={`status-indicator ${statusTone}`} />
              <div>
                <div className="sidebar-status-title">{toStatusText(status.connectionStatus, t)}</div>
                <div className="sidebar-status-sub">{status.deviceName || t.phoneNameFallback}</div>
              </div>
            </div>
          </div>
        </aside>

        <main className="page-area">
          {page === "home" && (
            <div className="page-grid">
              <section className="card hero-card">
                <div className="hero-header">
                  <div>
                    <h2>
                      <span className={`status-indicator ${statusTone}`} />
                      {status.connectionStatus === "connected" ? t.connectedPhone : t.waitingForDevice}
                    </h2>
                    <p>
                      {status.connectionStatus === "connected" ? t.streamingNow : t.connectGuideHint}
                    </p>
                  </div>
                  <div className={`hero-badge ${statusTone}`}>{toStatusText(status.connectionStatus, t)}</div>
                </div>

                <div className="audio-wave">
                  {Array.from({ length: 16 }).map((_, index) => (
                    <span
                      key={index}
                      className="audio-wave-bar"
                      style={{ animationDelay: `${index * 0.08}s` }}
                    />
                  ))}
                </div>

                <div className="hero-meta-grid">
                  <MetaCard label={t.deviceName} value={status.deviceName || t.phoneNameFallback} />
                  <MetaCard label={t.deviceIp} value={status.deviceIp || lastTargetIp || "-"} />
                  <MetaCard label={t.network} value={toNetworkText(status.networkType, t)} />
                  <MetaCard label={t.volume} value={`${status.volumePercent}%`} />
                </div>

                <div className="toolbar-row">
                  <button className="btn-primary" type="button" onClick={() => run("/api/audio/volume/decrease")}>
                    {t.volumeDown}
                  </button>
                  <button className="btn-secondary" type="button" onClick={() => run("/api/audio/mute/toggle")}>
                    {status.isMuted ? t.unmute : t.mute}
                  </button>
                  <button className="btn-primary" type="button" onClick={() => run("/api/audio/volume/increase")}>
                    {t.volumeUp}
                  </button>
                </div>
              </section>

              <section className="stats-grid">
                {stats.map((item) => (
                  <article className="card stat-card" key={item.label}>
                    <span>{item.label}</span>
                    <strong>{item.value}</strong>
                  </article>
                ))}
              </section>

              <section className="card log-card">
                <h3>{t.logsTitle}</h3>
                <pre>{logs.map((entry) => formatLogEntry(entry, settings.language, t)).join("\n") || t.noEvents}</pre>
              </section>
            </div>
          )}

          {page === "devices" && (
            <div className="page-grid">
              <section className="card">
                <div className="section-head">
                  <div>
                    <h2>{t.scanDevices}</h2>
                    <p>{t.scanHint}</p>
                  </div>
                  <button
                    className="btn-primary"
                    type="button"
                    onClick={async () => {
                      setScanStatus("scanning");
                      appendLog("scan.start");
                      await run("/api/discovery/start");
                    }}
                  >
                    {t.startScan}
                  </button>
                </div>

                <div className="scan-progress-wrap">
                  <div className="scan-progress-head">
                    <span>{t.scanStatus}</span>
                    <span>{scanStatus === "scanning" ? t.scanning : t.idle}</span>
                  </div>
                  <div className="progress-bar">
                    <div
                      className={`progress-fill ${scanStatus === "scanning" ? "scanning" : ""}`}
                      style={{ width: scanStatus === "scanning" ? "74%" : "0%" }}
                    />
                  </div>
                </div>

                <div className="manual-connect-row">
                  <button
                    className="btn-outline"
                    type="button"
                    onClick={async () => {
                      await run("/api/discovery/stop");
                      setScanStatus("idle");
                    }}
                  >
                    {t.stopScan}
                  </button>
                </div>
              </section>

              <section className="card">
                <h3>{t.availableDevices}</h3>
                <div className="device-list">
                  {devices.length === 0 && <p className="empty-text">{t.noDevices}</p>}
                  {devices.map((device) => {
                    return (
                      <article className="device-card" key={device.id}>
                        <div className="device-card-left">
                          <div className="device-avatar">M</div>
                          <div>
                            <strong>{device.name}</strong>
                            <p>{device.ip}</p>
                            <small>{device.status === "available" ? t.availableDevice : device.status}</small>
                          </div>
                        </div>
                      </article>
                    );
                  })}
                </div>
              </section>
            </div>
          )}

          {page === "settings" && (
            <div className="page-grid">
              <section className="card">
                <h2>{t.softwareSettings}</h2>
                <p className="section-copy">
                  {t.currentVirtualDevice} {status.virtualAudioDeviceName}
                </p>

                <div className="settings-stack">
                  <LanguageRow
                    label={t.languageLabel}
                    help={t.languageHelp}
                    value={settings.language}
                    options={[
                      { value: "zh-CN", label: t.chinese },
                      { value: "en-US", label: t.english }
                    ]}
                    onChange={(language) => setSettings((prev) => ({ ...prev, language }))}
                  />
                  <SettingRow
                    label={t.startOnBoot}
                    help={t.startOnBootHelp}
                    checked={settings.isStartOnBootEnabled}
                    onChange={(checked) => setSettings((prev) => ({ ...prev, isStartOnBootEnabled: checked }))}
                  />
                  <SettingRow
                    label={t.minimizeToTray}
                    help={t.minimizeToTrayHelp}
                    checked={settings.isMinimizeToTrayEnabled}
                    onChange={(checked) => setSettings((prev) => ({ ...prev, isMinimizeToTrayEnabled: checked }))}
                  />
                  <SettingRow
                    label={t.systemNotification}
                    help={t.systemNotificationHelp}
                    checked={settings.isNotificationEnabled}
                    onChange={(checked) => setSettings((prev) => ({ ...prev, isNotificationEnabled: checked }))}
                  />
                  <SettingRow
                    label={t.localMonitor}
                    help={t.localMonitorHelp}
                    checked={settings.isLocalMonitorEnabled}
                    onChange={(checked) => setSettings((prev) => ({ ...prev, isLocalMonitorEnabled: checked }))}
                  />
                </div>

                <div className="toolbar-row">
                  <button
                    className="btn-primary"
                    type="button"
                    onClick={async () => {
                      await run("/api/settings/save", settings);
                      appendLog("settings.saved");
                    }}
                  >
                    {t.saveSettings}
                  </button>
                  <button
                    className="btn-secondary"
                    type="button"
                    onClick={() => setSettings(defaultSettings(settings.language))}
                  >
                    {t.resetDefaults}
                  </button>
                  <button
                    className="btn-secondary"
                    type="button"
                    onClick={() => {
                      const systemLanguage = getSystemLanguage();
                      window.localStorage.removeItem("pc-ui-language");
                      setSettings((prev) => ({ ...prev, language: systemLanguage }));
                    }}
                  >
                    {settings.language === "en-US" ? "Follow System Language" : "恢复跟随系统语言"}
                  </button>
                  <button
                    className="btn-outline"
                    type="button"
                    onClick={async () => {
                      await run("/api/audio/local-monitor/toggle");
                      setSettings((prev) => ({
                        ...prev,
                        isLocalMonitorEnabled: !prev.isLocalMonitorEnabled
                      }));
                      appendLog(
                        settings.isLocalMonitorEnabled ? "local-monitor.disabled" : "local-monitor.enabled"
                      );
                    }}
                  >
                    {t.localMonitor}
                  </button>
                </div>
              </section>
            </div>
          )}

          {page === "help" && (
            <div className="page-grid">
              <section className="card">
                <div className="section-head">
                  <div>
                    <h2>{t.firstUseGuide}</h2>
                    <p>{t.guideHint}</p>
                  </div>
                  <button className="btn-primary" type="button" onClick={() => setShowGuide(true)}>
                    {t.viewTutorial}
                  </button>
                </div>

                <div className="steps-stack">
                  <StepCard index="1" title={t.step1Title} desc={t.step1Desc} />
                  <StepCard index="2" title={t.step2Title} desc={t.step2Desc} />
                  <StepCard index="3" title={t.step3Title} desc={t.step3Desc} />
                  <StepCard index="4" title={t.step4Title} desc={t.step4Desc} />
                </div>
              </section>

              <section className="card">
                <h3>{settings.language === "en-US" ? "VB-CABLE Install and Setup Guide" : "VB-CABLE 安装与设置指引"}</h3>
                <p>
                  {settings.language === "en-US"
                    ? "The installer will include this guide as well. If VB-CABLE is already installed, you can skip it."
                    : "安装包中也会包含这部分说明。如果你的电脑已经安装了 VB-CABLE，可以直接跳过。"}
                </p>
                <div className="install-guide-list">
                  <div>
                    {settings.language === "en-US"
                      ? "1. Finish installing the desktop app first."
                      : "1. 先完成桌面端软件安装。"}
                  </div>
                  <div>
                    {settings.language === "en-US"
                      ? "2. On first launch, check whether CABLE Input / CABLE Output are detected."
                      : "2. 打开软件后，确认系统中已识别 CABLE Input / CABLE Output。"}
                  </div>
                  <div>
                    {settings.language === "en-US"
                      ? "3. If they are missing, launch the local VB-CABLE installer with one click."
                      : "3. 如果没有识别到，可使用安装包内附带的 VB-CABLE 安装器。"}
                  </div>
                  <div>
                    {settings.language === "en-US"
                      ? "4. Route the system or target app output to VB-CABLE Input after installation."
                      : "4. 安装完成后，将系统或目标应用输出切换到 VB-CABLE Input。"}
                  </div>
                </div>
              </section>

            </div>
          )}

          {page === "feedback" && (
            <div className="page-grid">
              <section className="card">
                <div className="section-head">
                  <div>
                    <h2>{settings.language === "en-US" ? "Feedback Survey" : "使用反馈"}</h2>
                    <p>
                      {settings.language === "en-US"
                        ? "If you hit issues during testing or daily use, you can submit feedback directly here."
                        : "如果你在测试或日常使用中遇到问题，可以直接在这里提交反馈。"}
                    </p>
                  </div>
                  <a
                    className="btn-primary btn-link"
                    href={settings.language === "en-US" ? "https://tally.so/r/WOYkzk" : "https://tally.so/r/jaWqeY"}
                    target="_blank"
                    rel="noreferrer"
                  >
                    {settings.language === "en-US" ? "Open Survey" : "打开问卷"}
                  </a>
                </div>
                <div className="survey-frame-wrap">
                  <iframe
                    title={settings.language === "en-US" ? "Embedded Survey" : "内嵌反馈问卷"}
                    src={settings.language === "en-US" ? "https://tally.so/r/WOYkzk" : "https://tally.so/r/jaWqeY"}
                    className="survey-frame"
                    loading="lazy"
                  />
                </div>
              </section>
            </div>
          )}
        </main>
      </div>

      {showGuide && (
        <div className="modal-scrim" role="presentation" onClick={() => setShowGuide(false)}>
          <div className="modal-card" role="dialog" onClick={(e) => e.stopPropagation()}>
            <div className="modal-head">
              <h3>{t.welcomeTitle}</h3>
              <button className="btn-outline" type="button" onClick={() => setShowGuide(false)}>
                {t.close}
              </button>
            </div>
            <div className="steps-stack compact">
              <StepCard index="1" title={t.step1Title} desc={t.step1Desc} />
              <StepCard index="2" title={t.step2Title} desc={t.step2Desc} />
              <StepCard index="3" title={t.step3Title} desc={t.step3Desc} />
              <StepCard index="4" title={t.step4Title} desc={t.step4Desc} />
            </div>
          </div>
        </div>
      )}

    </div>
  );
}

function NavButton({
  page,
  target,
  label,
  onClick
}: {
  page: Page;
  target: Page;
  label: string;
  onClick: (page: Page) => void;
}) {
  return (
    <button
      className={`sidebar-item ${page === target ? "active" : ""}`}
      type="button"
      onClick={() => onClick(target)}
    >
      <span className="sidebar-item-bullet" />
      <span>{label}</span>
    </button>
  );
}

function MetaCard({ label, value }: { label: string; value: string }) {
  return (
    <div className="meta-card">
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

function SettingRow({
  label,
  help,
  checked,
  onChange
}: {
  label: string;
  help: string;
  checked: boolean;
  onChange: (checked: boolean) => void;
}) {
  return (
    <label className="setting-row">
      <div>
        <strong>{label}</strong>
        <p>{help}</p>
      </div>
      <input type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} />
    </label>
  );
}

function LanguageRow({
  label,
  help,
  value,
  options,
  onChange
}: {
  label: string;
  help: string;
  value: Language;
  options: Array<{ value: Language; label: string }>;
  onChange: (value: Language) => void;
}) {
  return (
    <label className="setting-row">
      <div>
        <strong>{label}</strong>
        <p>{help}</p>
      </div>
      <select className="language-select" value={value} onChange={(e) => onChange(e.target.value as Language)}>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </label>
  );
}

function StepCard({ index, title, desc }: { index: string; title: string; desc: string }) {
  return (
    <div className="step-item">
      <div className="step-number">{index}</div>
      <div>
        <h4>{title}</h4>
        <p>{desc}</p>
      </div>
    </div>
  );
}

function formatLogEntry(entry: LogEntry, language: Language, t: Copy): string {
  const isEnglish = language === "en-US";
  const message = (() => {
    switch (entry.kind) {
      case "status.updated":
        return isEnglish ? "Connection status updated" : "连接状态已更新";
      case "discovery.devices":
        return isEnglish ? "Device list updated" : "设备列表已更新";
      case "connection.error":
        return isEnglish ? "Connection error reported" : "连接异常已上报";
      case "audio.level":
        return isEnglish ? "Audio level updated" : "音量已更新";
      case "audio.playback":
        return isEnglish ? "Playback state updated" : "播放状态已更新";
      case "scan.start":
        return isEnglish ? "Started device scan" : "已开始扫描设备";
      case "scan.stop":
        return isEnglish ? "Stopped device scan" : "已停止扫描设备";
      case "connect.target":
        return `${t.reconnectingTo} ${entry.detail ?? ""}`.trim();
      case "reconnect.missing":
        return t.reconnectMissingTarget;
      case "settings.saved":
        return t.settingsSaved;
      case "local-monitor.enabled":
        return t.localMonitorEnabled;
      case "local-monitor.disabled":
        return t.localMonitorDisabled;
      default:
        return entry.kind;
    }
  })();

  return `${entry.at} ${message}`.trim();
}

function toStatusText(status: string, t: Copy): string {
  switch (status) {
    case "idle":
      return t.statusIdle;
    case "scanning":
      return t.statusScanning;
    case "connecting":
      return t.statusConnecting;
    case "connected":
      return t.statusConnected;
    case "reconnecting":
      return t.statusReconnecting;
    case "disconnected":
      return t.statusDisconnected;
    case "error":
      return t.statusError;
    default:
      return status;
  }
}

function toNetworkText(network: string, t: Copy): string {
  switch (network) {
    case "wifi":
      return t.networkWifi;
    case "ethernet":
      return t.networkEthernet;
    default:
      return t.networkUnknown;
  }
}
