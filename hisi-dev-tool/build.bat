@echo off
REM Read Maven build config from src\main\resources\application.yml, then run mvn.
REM Config keys (all optional, with env override + defaults baked into application.yml):
REM   maven.settings                  (env MAVEN_SETTINGS,        default D:/setting/settings-saas.xml)
REM   maven.repo                      (env MAVEN_REPO,            default D:/repository)
REM   maven.java-home                 (env MAVEN_JAVA_HOME,      default C:/Program Files/Java/jdk-17.0.3.1)
REM   maven.ssl.insecure              (env MAVEN_SSL_INSECURE,    default true)
REM   maven.ssl.allowall              (env MAVEN_SSL_ALLOWALL,    default true)
REM   maven.ssl.ignore-validity-dates (env MAVEN_SSL_IGNORE_VALIDITY_DATES, default true)
REM
REM Usage:
REM   build.bat [mvn args...]
REM   build.bat clean compile
REM   build.bat test -Dtest=FixChatServiceTest
REM   build.bat spring-boot:run
setlocal enabledelayedexpansion
cd /d "%~dp0"
set "APP_YML=src\main\resources\application.yml"

call :extract_value "settings"                 SETTINGS
call :extract_value "repo"                     REPO
call :extract_value "java-home"               JAVA_HOME_VAL
call :extract_value "insecure"                 SSL_INSECURE
call :extract_value "allowall"                 SSL_ALLOWALL
call :extract_value "ignore-validity-dates"    SSL_IGNORE_DATES

if defined MAVEN_SETTINGS          set "SETTINGS=%MAVEN_SETTINGS%"
if defined MAVEN_REPO              set "REPO=%MAVEN_REPO%"
if defined MAVEN_JAVA_HOME         set "JAVA_HOME_VAL=%MAVEN_JAVA_HOME%"
if defined MAVEN_SSL_INSECURE      set "SSL_INSECURE=%MAVEN_SSL_INSECURE%"
if defined MAVEN_SSL_ALLOWALL      set "SSL_ALLOWALL=%MAVEN_SSL_ALLOWALL%"
if defined MAVEN_SSL_IGNORE_VALIDITY_DATES set "SSL_IGNORE_DATES=%MAVEN_SSL_IGNORE_VALIDITY_DATES%"

if not exist "%SETTINGS%" (
    echo ERROR: settings file not found: %SETTINGS% 1>&2
    exit /b 1
)
if not exist "%REPO%\." (
    echo ERROR: repo dir not found: %REPO% 1>&2
    exit /b 1
)
if not exist "%JAVA_HOME_VAL%\." (
    echo ERROR: JAVA_HOME dir not found: %JAVA_HOME_VAL% 1>&2
    exit /b 1
)

set "JAVA_HOME=%JAVA_HOME_VAL%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

echo [build] settings=%SETTINGS%
echo [build] repo=%REPO%
echo [build] java-home=%JAVA_HOME_VAL%
echo [build] ssl.insecure=%SSL_INSECURE% ssl.allowall=%SSL_ALLOWALL% ignore.validity.dates=%SSL_IGNORE_DATES%
echo [build] mvn -s %SETTINGS% -Dmaven.repo.local=%REPO% ... %*
echo

mvn ^
    -s "%SETTINGS%" ^
    -Dmaven.repo.local="%REPO%" ^
    -Dmaven.wagon.http.ssl.insecure="%SSL_INSECURE%" ^
    -Dmaven.wagon.http.ssl.allowall="%SSL_ALLOWALL%" ^
    -Dmaven.wagon.http.ignore.validity.dates="%SSL_IGNORE_DATES%" ^
    %*
goto :eof

:extract_value
setlocal
set "KEY=%~1"
set "OUTVAR=%~2"
set "VALUE="
for /f "usebackq tokens=1,* delims=:" %%a in (`findstr /r /c:"^[ ]*%KEY%:" "%APP_YML%"`) do (
    set "RAWLINE=%%b"
    for /f "tokens=* delims= " %%x in ("!RAWLINE!") do set "VALUE=%%x"
    goto :got_value
)
:got_value
if defined VALUE (
    set "FIRST=!VALUE:~0,1!"
    if "!FIRST!"=="^"" set "VALUE=!VALUE:~1,-1!"
    if "!FIRST!"=="'" set "VALUE=!VALUE:~1,-1!"
)
if defined VALUE (
    echo !VALUE! | findstr /r /c:"^\$" >nul
    if !errorlevel! equ 0 (
        set "TMPVAL=!VALUE:~2,-1!"
        for /f "tokens=1,* delims=:" %%e in ("!TMPVAL!") do (
            set "ENVNAME=%%e"
            set "ENVDEF=%%f"
        )
        if "!TMPVAL!"=="!ENVNAME!" set "ENVDEF="
        if defined !ENVNAME! (
            call set "VALUE=%%!ENVNAME!%%"
        ) else (
            set "VALUE=!ENVDEF!"
        )
    )
)
endlocal & set "%OUTVAR%=%VALUE%"
goto :eof
