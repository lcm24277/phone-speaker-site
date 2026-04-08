param(
  [string]$VendorDir = "$PSScriptRoot\VBCABLE_Driver_Pack45"
)

$installer = Join-Path $VendorDir "VBCABLE_Setup_x64.exe"
if (-not (Test-Path -LiteralPath $installer)) {
  Write-Error "VB-CABLE installer not found at $installer"
  exit 2
}

Write-Output "Launching the official VB-CABLE installer..."
$process = Start-Process -FilePath $installer -WorkingDirectory $VendorDir -Wait -PassThru
if ($process.ExitCode -ne 0) {
  Write-Error "VB-CABLE installer failed with code: $($process.ExitCode)"
  exit $process.ExitCode
}

Write-Output "VB-CABLE installer finished."
