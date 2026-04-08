@echo off
setlocal
set ROOT=%~dp0
set DETECT=%ROOT%detect-vb-cable.ps1
set INSTALL=%ROOT%install-vb-cable.ps1

echo [1/3] Checking VB-CABLE devices...
powershell -ExecutionPolicy Bypass -File "%DETECT%"
if %ERRORLEVEL%==0 (
  echo.
  echo VB-CABLE is already available. You can now route Windows audio to CABLE Input.
  pause
  exit /b 0
)

echo.
echo [2/3] VB-CABLE was not detected.
echo If the official installer exists in the vendor folder, it will be launched next.
echo Vendor path: %ROOT%VBCABLE_Setup_x64.exe
echo.
choice /M "Launch the local VB-CABLE installer now"
if errorlevel 2 exit /b 1

powershell -ExecutionPolicy Bypass -File "%INSTALL%"
echo.
echo [3/3] Re-checking audio endpoints...
powershell -ExecutionPolicy Bypass -File "%DETECT%"
echo.
echo If detection succeeds, switch your Windows output or target app output to CABLE Input.
pause
