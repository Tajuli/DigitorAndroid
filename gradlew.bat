@echo off
setlocal

set "GRADLE_VERSION=9.5.0"
set "SCRIPT_DIR=%~dp0"
set "BOOTSTRAP_DIR=%SCRIPT_DIR%.gradle-bootstrap"
set "GRADLE_HOME=%BOOTSTRAP_DIR%\gradle-%GRADLE_VERSION%"
set "GRADLE_BAT=%GRADLE_HOME%\bin\gradle.bat"

if not exist "%GRADLE_BAT%" (
  echo Gradle %GRADLE_VERSION% not found. Downloading...
  if not exist "%BOOTSTRAP_DIR%" mkdir "%BOOTSTRAP_DIR%"
  powershell -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; $zip='%BOOTSTRAP_DIR%\gradle-%GRADLE_VERSION%-bin.zip'; Invoke-WebRequest 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile $zip; Expand-Archive -Path $zip -DestinationPath '%BOOTSTRAP_DIR%' -Force; Remove-Item $zip -Force"
  if errorlevel 1 exit /b 1
)

call "%GRADLE_BAT%" %*
exit /b %errorlevel%
