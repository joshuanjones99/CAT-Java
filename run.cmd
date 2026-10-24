@echo off
rem Starts the CAT-Java web UI on the controller's Java 17: run.cmd --rig lab
setlocal
if "%CAT_TOOLS_ROOT%"=="" (set "TOOLS=C:\CICD\Tools") else (set "TOOLS=%CAT_TOOLS_ROOT%")
set "JAVA=%TOOLS%\Java17\jdk-17.0.5+8\bin\java.exe"
rem A rig run attaches to the register's own browser over CDP; never download browsers.
set "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1"
if not exist "%JAVA%" (
  echo No Java 17 at %JAVA%. Install Java v1.2 from Artifactory into %TOOLS%\Java17.
  exit /b 1
)
if not exist "%~dp0target\cat-java.jar" (
  echo No target\cat-java.jar yet. Run build.cmd first.
  exit /b 1
)
"%JAVA%" -jar "%~dp0target\cat-java.jar" %*
