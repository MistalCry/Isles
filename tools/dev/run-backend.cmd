@echo off
rem Backend launcher for isles.bat (keeps redirection/quoting out of the main script).
cd /d "%~dp0..\..\backend"
mvn -B spring-boot:run > "%~dp0..\..\.run\backend.log" 2>&1
