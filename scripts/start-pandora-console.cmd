@echo off
setlocal

set "SCRIPT_DIR=%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%start-pandora-console.ps1" %*
set "PANDORA_EXIT_CODE=%ERRORLEVEL%"

echo.
echo [pandora] Console launcher finished with exit code %PANDORA_EXIT_CODE%.
if not "%PANDORA_NO_PAUSE%"=="1" pause

exit /b %PANDORA_EXIT_CODE%
