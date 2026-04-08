$keywords = @("CABLE Input", "VB-Audio")
$devices = Get-PnpDevice -Class "AudioEndpoint" -Status OK -ErrorAction SilentlyContinue

if (-not $devices) {
  Write-Error "No audio endpoints found."
  exit 3
}

$matched = $devices | Where-Object {
  $name = $_.FriendlyName
  $keywords | ForEach-Object { $name -like "*$_*" } | Where-Object { $_ } | Select-Object -First 1
}

if (-not $matched) {
  Write-Output "VB-CABLE 安装失败，或系统未检测到对应虚拟音频设备，请重新安装后重试。"
  exit 4
}

$first = $matched | Select-Object -First 1
Write-Output "Detected VB-CABLE device: $($first.FriendlyName)"
exit 0
