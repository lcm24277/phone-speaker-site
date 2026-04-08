$detectScript = Join-Path $PSScriptRoot "detect-vb-cable.ps1"
$installScript = Join-Path $PSScriptRoot "install-vb-cable.ps1"

& powershell.exe -ExecutionPolicy Bypass -File $detectScript | Out-Null
if ($LASTEXITCODE -eq 0) {
  exit 0
}

& powershell.exe -ExecutionPolicy Bypass -File $installScript
exit $LASTEXITCODE
