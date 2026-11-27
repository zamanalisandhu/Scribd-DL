@echo off
setlocal
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.13.102-hotspot"
set "MAVEN_HOME=C:\Users\TLP\maven\apache-maven-3.9.6"
set "PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%"

echo ========================================================
echo   Starting Scribd Downloader Pro (Java Spring Boot 3)
echo ========================================================
echo Java Version:
"%JAVA_HOME%\bin\java.exe" -version
echo.

call "%MAVEN_HOME%\bin\mvn.cmd" spring-boot:run
pause
