@echo off
rem Starts the CAT-Java web UI on the controller's Java 17: run.cmd --rig lab
rem Or runs one command and exits with its code (Main's dispatch table):
rem   run.cmd preflight --rig lab [--repair]
rem   run.cmd launch --rig lab --plan PETROCORE-118517 [--upgrade new_install] [--exec dev] [--no-xray]
rem               [--no-coverage] [--cc-onboard] [--fep NAME]... [--cache-only]   (run_plan.py's flags)
rem   run.cmd provision --rig lab
rem   run.cmd xray --run RUN_ID
rem   run.cmd close-day --rig lab
rem   run.cmd commander-snapshot ACTION ...
rem   run.cmd commander-connect --rig lab --lab Prod --email E --service-id ID
rem   run.cmd cloud-tunnel --rig lab [--stop]
rem When target\cat-java.jar is missing or older than the sources it builds it first, without the
rem tests (build.cmd runs them), staging Java 17 from Artifactory if the machine has none.
setlocal
if "%CAT_TOOLS_ROOT%"=="" (set "TOOLS=C:\CICD\Tools") else (set "TOOLS=%CAT_TOOLS_ROOT%")
set "JAVA=%TOOLS%\Java17\jdk-17.0.5+8\bin\java.exe"
rem A rig run attaches to the register's own browser over CDP; never download browsers.
set "PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD=1"
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bootstrap.ps1" -NeedsBuild
if errorlevel 10 goto :build
if errorlevel 1 goto :failed
goto :run

:build
echo Building CAT-Java (tests skipped; build.cmd runs them). The first build downloads Maven and
echo every library and can take several minutes.
set "CAT_BUILD_FROM_RUN=1"
call "%~dp0build.cmd" -q package -DskipTests
rem 3: a window has told the operator already (the shortcut's headless console).
if errorlevel 3 exit /b 1
if errorlevel 1 goto :failed
if not exist "%~dp0target\cat-java.jar" goto :failed
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bootstrap.ps1" -MarkBuilt
rem Java 17 v1.1 and the simulators: missing ones come from Artifactory; a failure here does not stop CAT.
"%JAVA%" -jar "%~dp0target\cat-java.jar" fetch-sims

:run
if not exist "%JAVA%" (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bootstrap.ps1" -Java -ToolsRoot "%TOOLS%"
  if errorlevel 3 exit /b 1
  if errorlevel 1 goto :failed
)
"%JAVA%" -jar "%~dp0target\cat-java.jar" %*
exit /b %errorlevel%

:failed
echo CAT-Java could not be built or started; see the lines above.
rem A double-clicked window closes when the script ends: keep it open so the reason can be read.
rem Under the CAT-Java.lnk shortcut's headless console, a message box says so instead.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bootstrap.ps1" -Failed
if not errorlevel 1 pause
exit /b 1
