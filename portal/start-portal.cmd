@echo off
set "NODE="
where node >nul 2>&1 && set "NODE=node"
if not defined NODE if exist "%ProgramFiles%\nodejs\node.exe" set "NODE=%ProgramFiles%\nodejs\node.exe"
if not defined NODE (
  echo Home dashboard was not started because Node.js was not found. The app will still install.
  exit /b 0
)
"%NODE%" "%~dp0start-if-down.js"
exit /b 0
