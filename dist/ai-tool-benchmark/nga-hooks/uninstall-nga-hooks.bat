@echo off
REM AI Tool Benchmark - Unregister hooks from CodeAgent CLI (nga)
setlocal

where nga >nul 2>nul
if errorlevel 1 (
    echo [ERROR] "nga" not found in PATH.
    pause
    exit /b 1
)

echo Removing nga hooks ...
echo (Errors below are harmless if a hook was never registered.)
echo.

call nga hooks remove chat.message        2>nul
call nga hooks remove tool.execute.before 2>nul
call nga hooks remove tool.execute.after  2>nul
call nga hooks remove session.stop        2>nul

echo.
echo Done. Verify with:  nga hooks list
pause
endlocal
