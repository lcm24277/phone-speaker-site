$renderCheck = Start-Process -FilePath "cmd.exe" -ArgumentList '/c reg query "HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Render" /s | findstr /i /c:"CABLE Input" /c:"VB-Audio Virtual Cable" >nul' -Wait -PassThru -WindowStyle Hidden
$captureCheck = Start-Process -FilePath "cmd.exe" -ArgumentList '/c reg query "HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Capture" /s | findstr /i /c:"CABLE Output" /c:"VB-Audio Virtual Cable" >nul' -Wait -PassThru -WindowStyle Hidden

if ($renderCheck.ExitCode -eq 0 -and $captureCheck.ExitCode -eq 0) {
  Write-Output "Detected VB-CABLE audio endpoints."
  exit 0
}

Write-Output "VB-CABLE was not detected on this PC."
exit 4
