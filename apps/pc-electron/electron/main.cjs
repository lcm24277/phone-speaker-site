const { app, BrowserWindow, Menu, ipcMain } = require("electron");
const path = require("node:path");
const { execFile, spawn } = require("node:child_process");
const fs = require("node:fs");

let pcServiceProcess = null;

function getAppRoot() {
  return app.isPackaged ? path.join(process.resourcesPath, "app.asar.unpacked") : path.join(__dirname, "..");
}

function runPowerShellScript(scriptPath) {
  return new Promise((resolve) => {
    execFile(
      "powershell.exe",
      ["-ExecutionPolicy", "Bypass", "-File", scriptPath],
      { windowsHide: true },
      (error, stdout, stderr) => {
        resolve({
          ok: !error,
          stdout: stdout || "",
          stderr: stderr || "",
          code: error && typeof error.code === "number" ? error.code : 0
        });
      }
    );
  });
}

function createWindow() {
  Menu.setApplicationMenu(null);
  const win = new BrowserWindow({
    width: 1200,
    height: 820,
    icon: path.join(__dirname, "..", "build", "icons", "app.ico"),
    backgroundColor: "#f5f7fb",
    webPreferences: {
      preload: path.join(__dirname, "preload.cjs"),
      contextIsolation: true
    }
  });
  const devUrl = process.env.PC_ELECTRON_DEV_URL;
  if (devUrl) {
    win.loadURL(devUrl);
    return;
  }
  win.loadFile(path.join(__dirname, "..", "dist", "index.html"));
}

function resolveServiceBinary() {
  const packaged = path.join(process.resourcesPath, "app.asar.unpacked", "service-bin", "pc-audio-service-rust.exe");
  const dev = path.join(__dirname, "..", "..", "services", "pc-audio-service-rust", "target", "release", "pc-audio-service-rust.exe");
  if (app.isPackaged && fs.existsSync(packaged)) return packaged;
  if (fs.existsSync(dev)) return dev;
  return null;
}

function startPcService() {
  if (pcServiceProcess) return;
  const serviceBinary = resolveServiceBinary();
  if (!serviceBinary) {
    console.warn("PC service binary not found");
    return;
  }
  pcServiceProcess = spawn(serviceBinary, [], {
    windowsHide: true,
    stdio: "ignore"
  });
  pcServiceProcess.on("exit", () => {
    pcServiceProcess = null;
  });
}

app.whenReady().then(() => {
  startPcService();
  createWindow();
});

app.on("before-quit", () => {
  if (pcServiceProcess && !pcServiceProcess.killed) {
    pcServiceProcess.kill();
  }
});

ipcMain.handle("vb-cable:detect", async () => {
  const root = getAppRoot();
  const scriptPath = path.join(root, "installer", "detect-vb-cable.ps1");
  const result = await runPowerShellScript(scriptPath);
  return {
    detected: result.ok,
    stdout: result.stdout,
    stderr: result.stderr,
    code: result.code
  };
});
