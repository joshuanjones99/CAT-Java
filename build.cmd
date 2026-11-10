@echo off
rem Builds CAT-Java on the controller's Java 17 (Java v1.2 in Artifactory), whatever JAVA_HOME says.
rem On a fresh machine it first stages that Java 17 from Artifactory (bootstrap.ps1; asks for the
rem Artifactory login once when none is saved); the Maven wrapper then fetches Maven and every library.
rem   build.cmd          compile, test, package, then stage the simulators (fetch-sims)
rem   build.cmd ARGS     ARGS go to Maven: build.cmd test, build.cmd test -Dtest=SomeTest, ...
setlocal
if "%CAT_TOOLS_ROOT%"=="" (set "TOOLS=C:\CICD\Tools") else (set "TOOLS=%CAT_TOOLS_ROOT%")
set "JAVA_HOME=%TOOLS%\Java17\jdk-17.0.5+8"
if not exist "%JAVA_HOME%\bin\java.exe" (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bootstrap.ps1" -Java -ToolsRoot "%TOOLS%"
  rem 3: a window has told the operator already (the shortcut's headless console).
  if errorlevel 3 exit /b 3
  if errorlevel 1 goto :failed
)
if not "%~1"=="" (
  call "%~dp0mvnw.cmd" %*
  if errorlevel 1 goto :failed
  exit /b 0
)
call "%~dp0mvnw.cmd" -q package
if errorlevel 1 goto :tests_failed
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bootstrap.ps1" -MarkBuilt
rem Java 17 v1.1 and the simulators: missing ones come from Artifactory; a failure here is not the build's.
"%JAVA_HOME%\bin\java.exe" -jar "%~dp0target\cat-java.jar" fetch-sims
exit /b 0

:tests_failed
echo.
echo The build failed, so target\cat-java.jar was not made or updated. The failing test classes:
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bootstrap.ps1" -FailedTests
echo Details are in target\surefire-reports. run.cmd builds without the tests when it has to.
:failed
call :pause_if_clicked
exit /b 1

rem A window opened by double-clicking closes as soon as the script ends; keep it open on a failure.
rem Not when run.cmd called this build: run.cmd reports the failure itself.
:pause_if_clicked
if defined CAT_BUILD_FROM_RUN exit /b 0
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bootstrap.ps1" -Clicked
if not errorlevel 1 pause
exit /b 0
