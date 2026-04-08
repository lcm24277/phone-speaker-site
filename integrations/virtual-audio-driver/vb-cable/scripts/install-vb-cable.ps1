param(
  [string]$VendorDir = "$PSScriptRoot\..\vendor"
)

$installer = Join-Path $VendorDir "VBCABLE_Setup_x64.exe"
if (-not (Test-Path -LiteralPath $installer)) {
  Write-Error "VB-CABLE installer not found at $installer"
  Write-Output "请放置官方 VB-CABLE 安装文件后重试。"
  exit 2
}

Write-Output "Launching VB-CABLE installer (silent if supported)..."
Start-Process -FilePath $installer -ArgumentList "/S" -Wait
if ($LASTEXITCODE -ne 0) {
  Write-Error "VB-CABLE installer failed with code: $LASTEXITCODE"
  exit $LASTEXITCODE
}

Write-Output "VB-CABLE installation finished."
