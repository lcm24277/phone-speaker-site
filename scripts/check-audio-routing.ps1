param(
  [string]$PcIp = "192.168.1.160",
  [string]$AdbPath = "C:\Users\turk\AppData\Local\Android\Sdk\platform-tools\adb.exe",
  [int]$WaitSec = 8
)

$ErrorActionPreference = "Stop"

function Read-DefaultRenderDeviceName {
  Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
[ComImport] [Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumeratorComObject {}
[Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceEnumerator { int NotImpl1(); int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice ppDevice); }
[Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDevice { int Activate(ref Guid id, int clsCtx, IntPtr actParams, out IPropertyStore propStore); int OpenPropertyStore(int stgmAccess, out IPropertyStore properties); }
[Guid("886d8eeb-8cf2-4446-8d02-cdba1dbdcf99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IPropertyStore { int GetCount(out int cProps); int GetAt(int iProp, out PROPERTYKEY pkey); int GetValue(ref PROPERTYKEY key, out PROPVARIANT pv); }
[StructLayout(LayoutKind.Sequential)] struct PROPERTYKEY { public Guid fmtid; public int pid; }
[StructLayout(LayoutKind.Explicit)] struct PROPVARIANT { [FieldOffset(0)] public short vt; [FieldOffset(8)] public IntPtr pwszVal; public string GetValue(){ return Marshal.PtrToStringUni(pwszVal); } }
public static class AudioDefaultName {
  public static string GetDefaultRenderDeviceName() {
    var e=(IMMDeviceEnumerator)(new MMDeviceEnumeratorComObject());
    IMMDevice dev; e.GetDefaultAudioEndpoint(0,1,out dev);
    IPropertyStore ps; dev.OpenPropertyStore(0,out ps);
    var key=new PROPERTYKEY{fmtid=new Guid("a45c254e-df1c-4efd-8020-67d146a850e0"),pid=14};
    PROPVARIANT v; ps.GetValue(ref key,out v);
    return v.GetValue();
  }
}
'@ -ErrorAction SilentlyContinue

  return [AudioDefaultName]::GetDefaultRenderDeviceName()
}

function Has-Marker {
  param([string]$Text, [string]$Marker)
  return $Text -match [regex]::Escape($Marker)
}

$root = Split-Path -Parent $PSScriptRoot
$pcLog = Join-Path $root "services\pc-audio-service-rust\logs\pc-audio-service.log"
$artDir = Join-Path $root "artifacts\routing-check"
$androidLog = Join-Path $artDir "android-logcat-routing.log"
$summaryJson = Join-Path $artDir "routing-check-summary.json"
New-Item -ItemType Directory -Path $artDir -Force | Out-Null

$port18730 = (netstat -ano | Select-String ":18730")
$port18731 = (netstat -ano | Select-String ":18731")
$renderName = Read-DefaultRenderDeviceName

& $AdbPath devices | Out-Null
& $AdbPath logcat -c | Out-Null
& $AdbPath shell am force-stop com.mobilespeaker.app | Out-Null
& $AdbPath shell am start -n com.mobilespeaker.app/.MainActivity --es auto_connect_ip $PcIp --es auto_connect_name PC-LIVE --ez auto_start_scan true --ez auto_start_foreground true | Out-Null
Start-Sleep -Seconds $WaitSec
& $AdbPath logcat -d -s MobileSpeakerAndroid:D *:S | Out-File -FilePath $androidLog -Encoding utf8

$androidRaw = Get-Content -Path $androidLog -Raw
$pcRaw = if (Test-Path $pcLog) { Get-Content -Path $pcLog -Raw } else { "" }

$result = [ordered]@{
  checkedAt = (Get-Date).ToString("o")
  pcIp = $PcIp
  pcServicePort18730Listening = [bool]$port18730
  pcServicePort18731Listening = [bool]$port18731
  pcCaptureDeviceIsVbCable = (Has-Marker -Text $pcRaw -Marker "PC_CAPTURE_DEVICE_SELECTED name=CABLE Output")
  defaultRenderDevice = $renderName
  defaultRenderLooksLikeVbCableInput = ($renderName -like "*CABLE Input*")
  androidConnectOk = (Has-Marker -Text $androidRaw -Marker "ANDROID_CONNECT_OK")
  androidPlaying = (Has-Marker -Text $androidRaw -Marker "ANDROID_PLAYBACK_PLAYING")
  androidRtp = (Has-Marker -Text $androidRaw -Marker "ANDROID_RTP_PACKET")
  androidLog = $androidLog
  pcLog = $pcLog
}

$result | ConvertTo-Json -Depth 5 | Out-File -FilePath $summaryJson -Encoding utf8
$result | ConvertTo-Json -Depth 5
