@echo off
setlocal

set "JAVA_HOME=D:\develop\Java\jdk-21"
set "PATH=%JAVA_HOME%\bin;%PATH%"

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo Java 21 was not found at %JAVA_HOME% >&2
    pause
    exit /b 1
)

call "%~dp0mvnw.cmd" %*
if errorlevel 1 pause
exit /b %ERRORLEVEL%
