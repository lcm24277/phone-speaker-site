const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("mobileSpeaker", {
  appVersion: "0.1.0",
  detectVBCable: () => ipcRenderer.invoke("vb-cable:detect")
});
