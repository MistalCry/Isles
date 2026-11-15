@echo off
rem Frontend launcher for isles.bat. Usage: run-frontend.cmd [port]
cd /d "%~dp0..\..\frontend"
set "PORT=%~1"
if "%PORT%"=="" set "PORT=5173"
if "%ISLES_LAN%"=="1" (set "HOST=0.0.0.0") else (set "HOST=127.0.0.1")
pnpm exec vite --port %PORT% --host %HOST% > "%~dp0..\..\.run\frontend.log" 2>&1
