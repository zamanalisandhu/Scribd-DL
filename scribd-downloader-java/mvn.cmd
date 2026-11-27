@echo off
set "JAVA_HOME=C:\Program Files\Microsoft\jdk-21.0.13.102-hotspot"
set "MAVEN_HOME=C:\Users\TLP\maven\apache-maven-3.9.6"
set "PATH=%JAVA_HOME%\bin;%MAVEN_HOME%\bin;%PATH%"
"%MAVEN_HOME%\bin\mvn.cmd" %*
