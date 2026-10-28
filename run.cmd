@echo off
rem Starts the CAT-Java web UI on the controller's Java 17: run.cmd --rig lab
rem Or runs one command and exits with its code (Main's dispatch table):
rem   run.cmd preflight --rig lab [--no-repair]
rem   run.cmd launch --rig lab --plan PETROCORE-118517 [--upgrade new_install] [--exec dev] [--no-xray]
rem               [--no-coverage] [--cc-onboard] [--fep NAME]... [--cache-only]   (run_plan.py's flags)
rem   run.cmd provision --rig lab
rem   run.cmd xray --run RUN_ID
rem   run.cmd close-day --rig lab
rem   run.cmd commander-snapshot ACTION ...
rem   run.cmd commander-connect --rig lab --lab Prod --email E --service-id ID
rem   run.cmd cloud-tunnel --rig lab [--stop]
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
