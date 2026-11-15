@echo off
rem ============================================================================
rem  群岛（Isles）一键启动 / 停止脚本
rem  用法：
rem    isles.bat                 打开菜单
rem    isles.bat start           启动后端 + 前端（默认 8080 / 5173）
rem    isles.bat start 8082 5174 指定端口启动
rem    isles.bat stop            停止（默认端口）
rem    isles.bat stop 8082 5174 停止指定端口
rem    isles.bat restart         重启
rem    isles.bat status          查看状态
rem  说明：
rem    - 自动读取项目根目录 .env.local（DB/JWT 等），无需手动设环境变量
rem    - 日志写入 .run\backend.log、.run\frontend.log
rem    - 进程 PID 写入 .run\*.pid，停止时按 PID + 端口双重清理
rem    - 设 ISLES_NO_BROWSER=1 可禁止启动后自动打开浏览器
rem    - 注意：stop 会结束占用指定端口的进程（默认 8080/5173），请确认端口归属
rem ============================================================================
chcp 936 >nul
setlocal EnableExtensions
cd /d "%~dp0"

set "ROOT=%CD%"
set "RUNDIR=%ROOT%\.run"
if not exist "%RUNDIR%" mkdir "%RUNDIR%" >nul 2>&1

set "ACTION=%~1"
set "BACKEND_PORT=%~2"
set "FRONTEND_PORT=%~3"
if "%BACKEND_PORT%"=="" set "BACKEND_PORT=8080"
if "%FRONTEND_PORT%"=="" set "FRONTEND_PORT=5173"

rem ---- 读取 .env.local（KEY=VALUE，# 开头为注释）----
if exist "%ROOT%\.env.local" (
  for /f "usebackq eol=# tokens=1* delims==" %%A in ("%ROOT%\.env.local") do (
    if not "%%~A"=="" if not "%%~B"=="" set "%%~A=%%~B"
  )
)

setlocal EnableDelayedExpansion

if /i "%ACTION%"=="start" goto run_start
if /i "%ACTION%"=="stop" goto run_stop
if /i "%ACTION%"=="restart" goto run_restart
if /i "%ACTION%"=="status" goto run_status
if "%ACTION%"=="" goto menu
echo [错误] 未知参数：%ACTION%
goto usage

rem ============================================================================
rem  菜单
rem ============================================================================
:menu
echo.
echo ==========================================
echo   群岛 Isles - 一键启动 / 停止
echo ==========================================
echo   1. 启动（后端 %BACKEND_PORT% / 前端 %FRONTEND_PORT%）
echo   2. 停止
echo   3. 重启
echo   4. 查看状态
echo   0. 退出
echo.
set "CHOICE="
set /p "CHOICE=请输入序号后回车: "
if "%CHOICE%"=="1" goto run_start
if "%CHOICE%"=="2" goto run_stop
if "%CHOICE%"=="3" goto run_restart
if "%CHOICE%"=="4" goto run_status
if "%CHOICE%"=="0" goto done
echo 无效选择，请重试。
goto menu

rem ============================================================================
rem  启动
rem ============================================================================
:run_start
call :start_stack
if errorlevel 1 (
  echo.
  echo [失败] 启动未完成，请查看 .run\backend.log / .run\frontend.log
)
goto pause_end

:start_stack
call :check_prereq
if errorlevel 1 exit /b 1

call :port_listening %BACKEND_PORT%
if not errorlevel 1 (
  echo [提示] 端口 %BACKEND_PORT% 已在监听，可能已有后端在运行。
  echo        如需重启请先执行：isles.bat stop %BACKEND_PORT% %FRONTEND_PORT%
  exit /b 1
)
call :port_listening %FRONTEND_PORT%
if not errorlevel 1 (
  echo [提示] 端口 %FRONTEND_PORT% 已在监听，可能已有前端在运行。
  echo        如需重启请先执行：isles.bat stop %BACKEND_PORT% %FRONTEND_PORT%
  exit /b 1
)

call :port_listening 3306
if errorlevel 1 (
  echo [警告] MySQL 3306 未监听，后端可能启动失败（请先启动 MySQL）。
)
call :port_listening 1234
if errorlevel 1 (
  echo [提示] LM Studio 1234 未监听：AI 功能会提示"未配置/不可用"，不影响其他功能。
)

echo.
echo [1/3] 启动后端（端口 %BACKEND_PORT%）...
set "SERVER__PORT=%BACKEND_PORT%"
set "VITE_API_TARGET=http://127.0.0.1:%BACKEND_PORT%"
rem 浏览器用 localhost 或 127.0.0.1 访问属不同 Origin，必须同时放行，否则后端 CORS 返回 403
set "CORS_ALLOWED_ORIGINS=http://localhost:%FRONTEND_PORT%,http://127.0.0.1:%FRONTEND_PORT%,http://localhost:*,http://127.0.0.1:*"
start "isles-backend" /min "%ROOT%\tools\dev\run-backend.cmd"
echo       等待健康检查 http://127.0.0.1:%BACKEND_PORT%/actuator/health ...
call :wait_http "http://127.0.0.1:%BACKEND_PORT%/actuator/health" 150
if errorlevel 1 (
  echo [错误] 后端 300 秒内未就绪，日志末尾：
  powershell -NoProfile -Command "if (Test-Path '.run\backend.log') { Get-Content '.run\backend.log' -Tail 25 }"
  exit /b 1
)
call :capture_pid %BACKEND_PORT% "%RUNDIR%\backend.pid"
echo       后端已就绪。

echo.
echo [2/3] 启动前端（端口 %FRONTEND_PORT%）...
if not exist "%ROOT%\frontend\node_modules" (
  echo       首次运行，安装前端依赖（pnpm install）...
  pushd "%ROOT%\frontend"
  call pnpm install
  popd
)
start "isles-frontend" /min "%ROOT%\tools\dev\run-frontend.cmd" %FRONTEND_PORT%
echo       等待前端 http://127.0.0.1:%FRONTEND_PORT%/ ...
call :wait_http "http://127.0.0.1:%FRONTEND_PORT%/" 90
if errorlevel 1 (
  echo [错误] 前端 180 秒内未就绪，日志末尾：
  powershell -NoProfile -Command "if (Test-Path '.run\frontend.log') { Get-Content '.run\frontend.log' -Tail 25 }"
  exit /b 1
)
call :capture_pid %FRONTEND_PORT% "%RUNDIR%\frontend.pid"
echo       前端已就绪。

echo.
echo [3/3] 打开浏览器 ...
if not defined ISLES_NO_BROWSER start "" "http://127.0.0.1:%FRONTEND_PORT%"
echo.
echo ==========================================
echo   启动完成
echo   前端：http://127.0.0.1:%FRONTEND_PORT%
echo   后端：http://127.0.0.1:%BACKEND_PORT%/actuator/health
echo   日志：.run\backend.log / .run\frontend.log
echo   停止：isles.bat stop %BACKEND_PORT% %FRONTEND_PORT%
echo ==========================================
exit /b 0

rem ============================================================================
rem  停止
rem ============================================================================
:run_stop
call :stop_stack
goto pause_end

:stop_stack
echo.
echo 正在停止后端（端口 %BACKEND_PORT%）...
call :kill_pid_file "%RUNDIR%\backend.pid"
call :kill_port %BACKEND_PORT%
echo 正在停止前端（端口 %FRONTEND_PORT%）...
call :kill_pid_file "%RUNDIR%\frontend.pid"
call :kill_port %FRONTEND_PORT%

rem 兜底：按启动时的窗口标题清理残留进程树
taskkill /FI "WINDOWTITLE eq isles-backend*" /T /F >nul 2>&1
taskkill /FI "WINDOWTITLE eq isles-frontend*" /T /F >nul 2>&1

call :port_listening %BACKEND_PORT%
if errorlevel 1 (echo       后端已停止。) else (echo [警告] 端口 %BACKEND_PORT% 仍在监听。)
call :port_listening %FRONTEND_PORT%
if errorlevel 1 (echo       前端已停止。) else (echo [警告] 端口 %FRONTEND_PORT% 仍在监听。)
exit /b 0

rem ============================================================================
rem  重启 / 状态
rem ============================================================================
:run_restart
call :stop_stack
call :start_stack
if errorlevel 1 (
  echo [失败] 重启未完成，请查看日志。
)
goto pause_end

:run_status
echo.
echo ---- 服务状态 ----
call :port_listening 3306
if errorlevel 1 (echo   MySQL       : 未监听 3306) else (echo   MySQL       : 正常 3306)
call :port_listening 1234
if errorlevel 1 (echo   LM Studio   : 未监听 1234) else (echo   LM Studio   : 正常 1234)
call :port_listening %BACKEND_PORT%
if errorlevel 1 (echo   后端        : 未启动 %BACKEND_PORT%) else (echo   后端        : 监听中 %BACKEND_PORT%)
curl -s -f -m 3 "http://127.0.0.1:%BACKEND_PORT%/actuator/health" >nul 2>&1
if errorlevel 1 (echo   后端健康    : 不可用) else (echo   后端健康    : UP)
call :port_listening %FRONTEND_PORT%
if errorlevel 1 (echo   前端        : 未启动 %FRONTEND_PORT%) else (echo   前端        : 监听中 %FRONTEND_PORT%)
echo ------------------
goto pause_end

rem ============================================================================
rem  子过程
rem ============================================================================

rem 前置工具检查
:check_prereq
where mvn >nul 2>&1
if errorlevel 1 (
  echo [错误] 未找到 mvn，请安装 Maven 并加入 PATH。
  exit /b 1
)
where java >nul 2>&1
if errorlevel 1 (
  echo [错误] 未找到 java，请安装 JDK 17 并加入 PATH。
  exit /b 1
)
where pnpm >nul 2>&1
if errorlevel 1 (
  echo [错误] 未找到 pnpm，请先安装：npm install -g pnpm
  exit /b 1
)
where curl >nul 2>&1
if errorlevel 1 (
  echo [错误] 未找到 curl（Windows 10 1803+ 自带），无法做健康检查。
  exit /b 1
)
if not exist "%ROOT%\tools\dev\run-backend.cmd" (
  echo [错误] 缺少 tools\dev\run-backend.cmd，请检查仓库完整性。
  exit /b 1
)
exit /b 0

rem 判断端口是否被监听：监听返回 0，否则 1
:port_listening
netstat -ano | findstr /R /C:":%~1 .*LISTENING" >nul 2>&1
if errorlevel 1 exit /b 1
exit /b 0

rem 轮询 HTTP：%1=url %2=最大尝试次数（每次间隔 2s）
:wait_http
set /a WAIT_TRY=0
:wait_http_loop
curl -s -f -m 2 "%~1" >nul 2>&1
if not errorlevel 1 exit /b 0
set /a WAIT_TRY+=1
if !WAIT_TRY! GEQ %~2 exit /b 1
ping -n 3 127.0.0.1 >nul
goto wait_http_loop

rem 记录端口监听进程 PID：%1=端口 %2=PID 文件
:capture_pid
set "CPID="
for /f "tokens=5" %%P in ('netstat -ano ^| findstr /R /C:":%~1 .*LISTENING"') do (
  if not defined CPID set "CPID=%%P"
)
if defined CPID >"%~2" echo !CPID!
exit /b 0

rem 按 PID 文件结束进程树
:kill_pid_file
if not exist "%~1" exit /b 0
set "KILL_PID="
set /p KILL_PID=<"%~1"
if defined KILL_PID taskkill /PID !KILL_PID! /T /F >nul 2>&1
del /q "%~1" >nul 2>&1
exit /b 0

rem 按端口结束监听进程树（兜底）
:kill_port
for /f "tokens=5" %%P in ('netstat -ano ^| findstr /R /C:":%~1 .*LISTENING"') do (
  taskkill /PID %%P /T /F >nul 2>&1
)
exit /b 0

rem ============================================================================
:usage
echo.
echo 用法：isles.bat [start^|stop^|restart^|status] [后端端口] [前端端口]
goto pause_end

:pause_end
rem 仅菜单/双击（无参数）时暂停；命令行带参数执行完直接返回，便于脚本/CI 调用
if "%ACTION%"=="" pause
goto done

:done
endlocal
endlocal
exit /b 0
