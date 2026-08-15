@echo off
setlocal enabledelayedexpansion
set GRADLE_VERSION=8.11.1
set GRADLE_SHA256=f397b287023acdba1e9f6fc5ea72d22dd63669d59ed4a289a29b1a76eee151c6
if "%GRADLE_USER_HOME%"=="" set GRADLE_USER_HOME=%USERPROFILE%\.gradle
set BOOTSTRAP_ROOT=%GRADLE_USER_HOME%\kaw-bootstrap
set GRADLE_HOME=%BOOTSTRAP_ROOT%\gradle-%GRADLE_VERSION%
set ZIP_PATH=%BOOTSTRAP_ROOT%\gradle-%GRADLE_VERSION%-bin.zip
if not exist "%GRADLE_HOME%\bin\gradle.bat" (
  if not exist "%BOOTSTRAP_ROOT%" mkdir "%BOOTSTRAP_ROOT%"
  if not exist "%ZIP_PATH%" powershell -NoProfile -ExecutionPolicy Bypass -Command "Invoke-WebRequest -UseBasicParsing -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%ZIP_PATH%'"
  for /f %%H in ('powershell -NoProfile -Command "(Get-FileHash -Algorithm SHA256 '%ZIP_PATH%').Hash.ToLower()"') do set ACTUAL_SHA=%%H
  if not "!ACTUAL_SHA!"=="%GRADLE_SHA256%" (
    echo Gradle distribution checksum mismatch.
    del "%ZIP_PATH%"
    exit /b 1
  )
  powershell -NoProfile -ExecutionPolicy Bypass -Command "Expand-Archive -Force '%ZIP_PATH%' '%BOOTSTRAP_ROOT%'"
)
call "%GRADLE_HOME%\bin\gradle.bat" %*
