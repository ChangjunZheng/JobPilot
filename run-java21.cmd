@echo off
setlocal

set "JAVA_HOME=D:\develop\Java\jdk-21"
set "PATH=%JAVA_HOME%\bin;%PATH%"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo Java 21 was not found at %JAVA_HOME% >&2
    pause
    exit /b 1
)

rem No argument: clean then start the Spring Boot app. With arguments: clean + pass them through.
if "%~1"=="" (
    call "%~dp0mvnw.cmd" clean spring-boot:run
) else (
    call "%~dp0mvnw.cmd" clean %*
)

rem Capture the exit code before pause, which would overwrite it with 0.
set "MVN_EXIT=%ERRORLEVEL%"

rem Always pause at the end so the window stays open for reading startup logs
pause
exit /b %MVN_EXIT%
