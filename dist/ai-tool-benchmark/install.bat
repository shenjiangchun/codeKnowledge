@echo off
REM AI Tool Benchmark - Windows installer
REM Copies skill files to %USERPROFILE%\.claude\skills\ai-tool-benchmark\

setlocal
set "SRC=%~dp0"
set "DEST=%USERPROFILE%\.claude\skills\ai-tool-benchmark"
set "DATA=%USERPROFILE%\.claude\ai-bench"

echo ==========================================
echo  AI Tool Benchmark - Installer (Windows)
echo ==========================================
echo.

REM Check Python
where python >nul 2>nul
if errorlevel 1 (
    echo [ERROR] python is not in PATH. Please install Python 3.8+ and re-run.
    pause
    exit /b 1
)
python --version

echo.
echo Source : %SRC%
echo Target : %DEST%
echo Data   : %DATA%
echo.

if not exist "%DEST%" mkdir "%DEST%"
if not exist "%DATA%\sessions" mkdir "%DATA%\sessions"

copy /Y "%SRC%bench.py"     "%DEST%\bench.py"     >nul
copy /Y "%SRC%report.py"    "%DEST%\report.py"    >nul
copy /Y "%SRC%SKILL.md"     "%DEST%\SKILL.md"     >nul
copy /Y "%SRC%uninstall.py" "%DEST%\uninstall.py" >nul

echo [OK] Files copied.
echo.
echo Smoke test:
python "%DEST%\bench.py" list
if errorlevel 1 (
    echo [ERROR] Smoke test failed.
    pause
    exit /b 1
)

echo.
echo ==========================================
echo  Install complete.
echo.
echo  Next steps:
echo    1) Read QUICKSTART.md
echo    2) python "%DEST%\bench.py" start --tool ^<name^> --task ^<task^>
echo    3) (Optional) For Claude Code, merge hooks-snippet.json into:
echo       %USERPROFILE%\.claude\settings.json
echo ==========================================
pause
endlocal
