@echo off
REM AI Tool Benchmark - Register hooks into CodeAgent CLI (nga)
setlocal
set "DIR=%~dp0"

where nga >nul 2>nul
if errorlevel 1 (
    echo [ERROR] "nga" not found in PATH. Install CodeAgent CLI first.
    pause
    exit /b 1
)

echo Registering nga hooks (path: %DIR%) ...
echo.

call nga hooks add chat.message         "%DIR%chat-message.js"        "AI Tool Benchmark - capture chat messages"
call nga hooks add tool.execute.before  "%DIR%tool-execute-before.js" "AI Tool Benchmark - capture tool calls (before)"
call nga hooks add tool.execute.after   "%DIR%tool-execute-after.js"  "AI Tool Benchmark - capture tool calls (after)"
REM Optional: only register if your nga build emits this event
call nga hooks add session.stop         "%DIR%session-stop.js"        "AI Tool Benchmark - capture session stop" 2>nul

echo.
echo Done. Verify with:  nga hooks list
pause
endlocal
