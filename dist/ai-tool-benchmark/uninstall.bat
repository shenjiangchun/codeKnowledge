@echo off
REM AI Tool Benchmark - Windows one-click uninstaller
setlocal
set "SCRIPT_DIR=%~dp0"
set "TARGET=%USERPROFILE%\.claude\skills\ai-tool-benchmark\uninstall.py"

REM Prefer the installed copy (always present); fall back to local copy.
if exist "%TARGET%" (
    set "RUN=%TARGET%"
) else (
    set "RUN=%SCRIPT_DIR%uninstall.py"
)

where python >nul 2>nul
if errorlevel 1 (
    echo [ERROR] python not in PATH. Install Python 3.8+ and re-run.
    pause
    exit /b 1
)

echo.
echo === Step 1/2: DRY-RUN preview (no changes yet) ===
python "%RUN%"
if errorlevel 1 (
    echo [ERROR] dry-run failed.
    pause
    exit /b 1
)

echo.
set /p CONFIRM="Proceed with actual removal? Type YES to confirm: "
if /I not "%CONFIRM%"=="YES" (
    echo [Abort] No changes made.
    pause
    exit /b 0
)

echo.
set /p PURGE="Also delete recorded session data in ~/.claude/ai-bench/ ? (y/N): "
if /I "%PURGE%"=="y" (
    python "%RUN%" --yes --purge-data
) else (
    python "%RUN%" --yes
)

echo.
echo Done.
pause
endlocal
