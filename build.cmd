@echo off
rem Builds CAT-Java on the controller's Java 17 (Java v1.2 in Artifactory), whatever
rem JAVA_HOME says. Arguments go to Maven: build.cmd (package), build.cmd test, ...
setlocal
if "%CAT_TOOLS_ROOT%"=="" (set "TOOLS=C:\CICD\Tools") else (set "TOOLS=%CAT_TOOLS_ROOT%")
set "JAVA_HOME=%TOOLS%\Java17\jdk-17.0.5+8"
if not exist "%JAVA_HOME%\bin\java.exe" (
  echo No Java 17 at %JAVA_HOME%. Install Java v1.2 from Artifactory into %TOOLS%\Java17.
  exit /b 1
)
if "%~1"=="" (call "%~dp0mvnw.cmd" -q package) else (call "%~dp0mvnw.cmd" %*)
