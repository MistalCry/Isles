@echo off
rem Frontend launcher for isles.bat. Usage: run-frontend.cmd [port]
cd /d "%~dp0..\..\frontend"
set "PORT=%~1"
if "%PORT%"=="" set "PORT=5173"
pnpm exec vite --port %PORT% --host 127.0.0.1 > "%~dp0..\..\.run\frontend.log" 2>&1
