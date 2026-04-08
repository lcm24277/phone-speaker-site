param(
  [Parameter(Mandatory = $true)]
  [string]$PcIp,
  [string]$DeviceSerial = "",
  [int]$DurationSec = 45,
  [string]$WorkspaceRoot = "C:\Program_Data\Codex\mobile-speaker"
)

$ErrorActionPreference = "Stop"

function Invoke-Adb {
  param([string[]]$Args)
  if ($DeviceSerial -and $DeviceSerial.Trim().Length -gt 0) {
    & adb -s $DeviceSerial @Args
  } else {
    & adb @Args
  }
}

function Wait-HttpReady {
  param([string]$Url, [int]$Retry = 80)
  for ($i = 0; $i -lt $Retry; $i++) {
    try {
      $resp = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2
      if ($resp.StatusCode -ge 200 -and $resp.StatusCode -lt 300) { return }
    } catch {}
    Start-Sleep -Milliseconds 250
  }
  throw "HTTP not ready: $Url"
}

$null = New-Item -ItemType Directory -Force -Path (Join-Path $WorkspaceRoot "artifacts\e2e")
$artifactDir = Join-Path $WorkspaceRoot "artifacts\e2e"
$pcServiceDir = Join-Path $WorkspaceRoot "services\pc-audio-service-rust"
$pcStdout = Join-Path $artifactDir "pc-service-stdout.log"
$pcStderr = Join-Path $artifactDir "pc-service-stderr.log"
$androidLogcat = Join-Path $artifactDir "android-logcat.log"
$acceptanceJson = Join-Path $artifactDir "acceptance-result.json"
$pcRuntimeLog = Join-Path $pcServiceDir "logs\pc-audio-service.log"
$runSinceUtc = (Get-Date).ToUniversalTime().ToString("o")

if (-not (Get-Command adb -ErrorAction SilentlyContinue)) {
  throw "adb not found in PATH"
}
if (-not (Get-Command cargo -ErrorAction SilentlyContinue)) {
  throw "cargo not found in PATH"
}

if (Test-Path $androidLogcat) { Remove-Item -Force $androidLogcat }

Write-Host "[E2E] Starting Rust service..."
$pcProcess = Start-Process -FilePath "cargo" -ArgumentList "run" -WorkingDirectory $pcServiceDir -PassThru -RedirectStandardOutput $pcStdout -RedirectStandardError $pcStderr

try {
  Wait-HttpReady -Url "http://127.0.0.1:18730/api/status"
  Write-Host "[E2E] Rust service is ready."

  Invoke-Adb @("wait-for-device")
  Invoke-Adb @("logcat", "-c")
  Write-Host "[E2E] Launching Android app with auto-connect intent..."
  Invoke-Adb @(
    "shell", "am", "start",
    "-n", "com.mobilespeaker.app/.MainActivity",
    "--es", "auto_connect_ip", $PcIp,
    "--es", "auto_connect_name", "PC-E2E",
    "--ez", "auto_start_scan", "true",
    "--ez", "auto_start_foreground", "true"
  )

  Write-Host "[E2E] Waiting $DurationSec seconds for stream stabilization..."
  Start-Sleep -Seconds $DurationSec

  Write-Host "[E2E] Collecting Android logcat..."
  Invoke-Adb @("logcat", "-d", "-s", "MobileSpeakerAndroid:D", "*:S") | Out-File -FilePath $androidLogcat -Encoding utf8

  if (-not (Test-Path $pcRuntimeLog)) {
    throw "PC runtime log missing: $pcRuntimeLog"
  }

  Write-Host "[E2E] Running acceptance verifier..."
  $verifyOutput = node (Join-Path $WorkspaceRoot "scripts\verify-e2e-acceptance.mjs") --pc $pcRuntimeLog --android $androidLogcat --since $runSinceUtc
  $verifyOutput | Out-File -FilePath $acceptanceJson -Encoding utf8
  Write-Host $verifyOutput
  Write-Host "[E2E] PASS. acceptance-result.json => $acceptanceJson"
} finally {
  if ($pcProcess -and -not $pcProcess.HasExited) {
    Write-Host "[E2E] Stopping Rust service..."
    Stop-Process -Id $pcProcess.Id -Force
  }
}
