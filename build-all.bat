@echo off
chcp 65001 >nul
setlocal enabledelayedexpansion

:: ============================================================
:: HiSi DevTool - Build All Script
:: Usage:
::   build-all.bat              Build all 3 projects
::   build-all.bat backend      Backend only
::   build-all.bat frontend     Frontend only
::   build-all.bat mcp          MCP Server only
::   build-all.bat desktop      Electron desktop packaging
::   build-all.bat --with-test  Run tests during build
:: ============================================================

set "ROOT_DIR=%~dp0"
set "BACKEND_DIR=%ROOT_DIR%hisi-dev-tool"
set "FRONTEND_DIR=%ROOT_DIR%hisi-dev-tool-frontend"
set "MCP_DIR=%ROOT_DIR%hisi-mcp-server"
set "DESKTOP_DIR=%ROOT_DIR%hisi-desktop"
set "DIST_DIR=%ROOT_DIR%dist"

set BUILD_BACKEND=1
set BUILD_FRONTEND=1
set BUILD_MCP=1
set BUILD_DESKTOP=0
set SKIP_TEST=1
set HAS_ERROR=0

:: Parse args
:parse_args
if "%~1"=="" goto :args_done
if /i "%~1"=="backend"     set BUILD_FRONTEND=0& set BUILD_MCP=0
if /i "%~1"=="frontend"    set BUILD_BACKEND=0& set BUILD_MCP=0
if /i "%~1"=="mcp"         set BUILD_BACKEND=0& set BUILD_FRONTEND=0
if /i "%~1"=="desktop"     set BUILD_DESKTOP=1
if /i "%~1"=="--with-test" set SKIP_TEST=0
shift
goto :parse_args
:args_done

echo.
echo ========================================================
echo   HiSi DevTool Build
echo ========================================================
echo.
echo   Root:      %ROOT_DIR%
echo   Backend=%BUILD_BACKEND%  Frontend=%BUILD_FRONTEND%  MCP=%BUILD_MCP%
echo   SkipTest=%SKIP_TEST%
echo.

:: ============================================================
:: 1. Backend (Spring Boot fat jar)
:: ============================================================
if not "%BUILD_BACKEND%"=="1" goto :after_backend

echo --------------------------------------------------------
echo   [1/3] Building Backend (Spring Boot)
echo --------------------------------------------------------

if not exist "%BACKEND_DIR%\pom.xml" (
    echo   [ERROR] pom.xml not found at "%BACKEND_DIR%"
    set HAS_ERROR=1
    goto :after_backend
)

pushd "%BACKEND_DIR%"

if "%SKIP_TEST%"=="1" (
    call mvn clean package -DskipTests -q
) else (
    call mvn clean package -q
)

if !errorlevel! neq 0 (
    echo   [FAIL] Backend build failed!
    set HAS_ERROR=1
    popd
    goto :after_backend
)

set "BACKEND_JAR="
for %%J in (target\*.jar) do (
    echo %%~nJ | findstr /C:"-plain" >nul 2>&1
    if !errorlevel! neq 0 set "BACKEND_JAR=%%J"
)

echo   [OK] Backend: !BACKEND_JAR!
popd

:after_backend

:: ============================================================
:: 2. Frontend (Vue 3 + Vite)
:: ============================================================
if not "%BUILD_FRONTEND%"=="1" goto :after_frontend

echo.
echo --------------------------------------------------------
echo   [2/3] Building Frontend (Vue 3 + TypeScript)
echo --------------------------------------------------------

if not exist "%FRONTEND_DIR%\package.json" (
    echo   [ERROR] package.json not found at "%FRONTEND_DIR%"
    set HAS_ERROR=1
    goto :after_frontend
)

pushd "%FRONTEND_DIR%"

if not exist "node_modules" (
    echo   [INFO] Installing dependencies...
    call npm install --silent
)

echo   [INFO] Type checking...
call npx vue-tsc --noEmit 2>nul
if !errorlevel! neq 0 (
    echo   [WARN] Type errors found, continuing build...
)

call npm run build
if !errorlevel! neq 0 (
    echo   [FAIL] Frontend build failed!
    set HAS_ERROR=1
    popd
    goto :after_frontend
)

echo   [OK] Frontend: dist/
popd

:after_frontend

:: ============================================================
:: 3. MCP Server (TypeScript)
:: ============================================================
if not "%BUILD_MCP%"=="1" goto :after_mcp

echo.
echo --------------------------------------------------------
echo   [3/3] Building MCP Server (TypeScript)
echo --------------------------------------------------------

if not exist "%MCP_DIR%\package.json" (
    echo   [ERROR] package.json not found at "%MCP_DIR%"
    set HAS_ERROR=1
    goto :after_mcp
)

pushd "%MCP_DIR%"

if not exist "node_modules" (
    echo   [INFO] Installing dependencies...
    call npm install --silent
)

call npm run build
if !errorlevel! neq 0 (
    echo   [FAIL] MCP Server build failed!
    set HAS_ERROR=1
    popd
    goto :after_mcp
)

echo   [OK] MCP Server: dist/
popd

:after_mcp

:: ============================================================
:: 4. Collect artifacts into dist/
:: ============================================================
echo.
echo --------------------------------------------------------
echo   Collecting build artifacts
echo --------------------------------------------------------

if not exist "%DIST_DIR%" mkdir "%DIST_DIR%"

:: Copy backend jar
if not "%BUILD_BACKEND%"=="1" goto :skip_copy_backend
for %%J in ("%BACKEND_DIR%\target\*.jar") do (
    echo %%~nJ | findstr /C:"-plain" >nul
    if !errorlevel! neq 0 (
        copy /Y "%%J" "%DIST_DIR%\hisi-backend.jar" >nul
        echo   [OK] dist\hisi-backend.jar
        goto :skip_copy_backend
    )
)
:skip_copy_backend

:: Copy frontend dist
if not "%BUILD_FRONTEND%"=="1" goto :skip_copy_frontend
if exist "%FRONTEND_DIR%\dist" (
    if exist "%DIST_DIR%\frontend" rmdir /S /Q "%DIST_DIR%\frontend"
    xcopy /E /I /Q /Y "%FRONTEND_DIR%\dist" "%DIST_DIR%\frontend" >nul
    echo   [OK] dist\frontend\
)
:skip_copy_frontend

:: Copy MCP dist
if not "%BUILD_MCP%"=="1" goto :skip_copy_mcp
if exist "%MCP_DIR%\dist" (
    if exist "%DIST_DIR%\mcp-server" rmdir /S /Q "%DIST_DIR%\mcp-server"
    xcopy /E /I /Q /Y "%MCP_DIR%\dist" "%DIST_DIR%\mcp-server" >nul
    copy /Y "%MCP_DIR%\package.json" "%DIST_DIR%\mcp-server\" >nul
    echo   [OK] dist\mcp-server\
)
:skip_copy_mcp

:: Copy default config
if not exist "%DIST_DIR%\config" mkdir "%DIST_DIR%\config"
if exist "%DIST_DIR%\config\application.yml" goto :skip_copy_config
if exist "%BACKEND_DIR%\src\main\resources\application.yml" (
    copy /Y "%BACKEND_DIR%\src\main\resources\application.yml" "%DIST_DIR%\config\application.yml" >nul
    echo   [OK] dist\config\application.yml
)
:skip_copy_config

:: ============================================================
:: 5. Generate start/stop scripts
:: ============================================================
call :gen_start_script
call :gen_stop_script

:: ============================================================
:: 6. Electron Desktop Packaging (optional, use: build-all.bat desktop)
:: ============================================================
if not "%BUILD_DESKTOP%"=="1" goto :after_desktop

echo.
echo --------------------------------------------------------
echo   [Extra] Packaging Electron Desktop App
echo --------------------------------------------------------

if not exist "%DESKTOP_DIR%\package.json" (
    echo   [ERROR] hisi-desktop not found
    set HAS_ERROR=1
    goto :after_desktop
)

:: Copy artifacts to desktop resources
echo   [INFO] Copying artifacts to hisi-desktop/resources...
if not exist "%DESKTOP_DIR%\resources\backend" mkdir "%DESKTOP_DIR%\resources\backend"
if not exist "%DESKTOP_DIR%\resources\frontend" mkdir "%DESKTOP_DIR%\resources\frontend"
if not exist "%DESKTOP_DIR%\resources\mcp-server" mkdir "%DESKTOP_DIR%\resources\mcp-server"
if not exist "%DESKTOP_DIR%\resources\config" mkdir "%DESKTOP_DIR%\resources\config"

if exist "%DIST_DIR%\hisi-backend.jar" copy /Y "%DIST_DIR%\hisi-backend.jar" "%DESKTOP_DIR%\resources\backend\" >nul
if exist "%DIST_DIR%\frontend" xcopy /E /I /Q /Y "%DIST_DIR%\frontend" "%DESKTOP_DIR%\resources\frontend" >nul
if exist "%DIST_DIR%\mcp-server" xcopy /E /I /Q /Y "%DIST_DIR%\mcp-server" "%DESKTOP_DIR%\resources\mcp-server" >nul
if exist "%DIST_DIR%\config\application.yml" copy /Y "%DIST_DIR%\config\application.yml" "%DESKTOP_DIR%\resources\config\" >nul

pushd "%DESKTOP_DIR%"

if not exist "node_modules" (
    echo   [INFO] Installing Electron dependencies...
    call npm install --silent
)

echo   [INFO] Building Electron app...
call npm run build
if !errorlevel! neq 0 (
    echo   [FAIL] Electron build failed!
    set HAS_ERROR=1
    popd
    goto :after_desktop
)

echo   [OK] Electron desktop app packaged
popd

:after_desktop

:: ============================================================
:: Done
:: ============================================================
echo.
echo ========================================================
if "%HAS_ERROR%"=="1" (
    echo   BUILD COMPLETED WITH ERRORS - check log above
) else (
    echo   ALL BUILDS SUCCESSFUL
)
echo.
echo   Output: %DIST_DIR%
echo.
echo   dist\
echo     hisi-backend.jar     Backend fat-jar
echo     frontend\            Frontend static files
echo     mcp-server\          MCP Server
echo     config\
echo       application.yml    External config (editable)
echo     start.bat            Start all services
echo     stop.bat             Stop all services
echo ========================================================
echo.

endlocal
if "%HAS_ERROR%"=="1" exit /b 1
exit /b 0

:: ============================================================
:: Subroutines
:: ============================================================

:gen_start_script
>"%DIST_DIR%\start.bat" (
    echo @echo off
    echo chcp 65001 ^>nul
    echo setlocal
    echo set "APP_DIR=%%~dp0"
    echo set "JAVA_OPTS=-Xms256m -Xmx1024m"
    echo echo.
    echo echo   HiSi DevTool Starting...
    echo echo.
    echo echo [1/2] Starting backend ^(port 8080^)...
    echo start "" /B java %%JAVA_OPTS%% -jar "%%APP_DIR%%hisi-backend.jar" --spring.config.additional-location="file:%%APP_DIR%%config/"
    echo echo [INFO] Waiting for backend...
    echo :wait_loop
    echo timeout /t 2 /nobreak ^>nul
    echo curl -s http://localhost:8080/ ^>nul 2^>^&1
    echo if %%errorlevel%% neq 0 goto :wait_loop
    echo echo [OK] Backend ready
    echo echo.
    echo if exist "%%APP_DIR%%mcp-server\index.js" ^(
    echo     echo [2/2] Starting MCP Server...
    echo     start "" /B node "%%APP_DIR%%mcp-server\index.js"
    echo     echo [OK] MCP Server started
    echo ^)
    echo echo.
    echo echo   All services started!
    echo echo   Open: http://localhost:8080
    echo echo   Press Ctrl+C to stop
    echo echo.
    echo pause
)
echo   [OK] dist\start.bat
goto :eof

:gen_stop_script
>"%DIST_DIR%\stop.bat" (
    echo @echo off
    echo echo Stopping HiSi DevTool services...
    echo for /f "tokens=5" %%%%p in ^('netstat -aon 2^>nul ^| findstr ":8080 " ^| findstr "LISTENING"'^) do ^(
    echo     taskkill /F /PID %%%%p ^>nul 2^>^&1
    echo ^)
    echo echo [OK] All services stopped
    echo pause
)
echo   [OK] dist\stop.bat
goto :eof
