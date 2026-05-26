@echo off
chcp 65001 >nul
setlocal

echo [MD3L] Full Build (Extracting runtime + MD3L.exe)...

echo [MD3L] 1. Building MD3L.exe...
call build-update.bat
if errorlevel 1 (
    echo build-update.bat failed!
    exit /b %ERRORLEVEL%
)

if not exist dist mkdir dist

echo [MD3L] 2. Generating runtime via jlink...
if exist dist\runtime rmdir /s /q dist\runtime

set "JLINK_EXE="
for /f "delims=" %%i in ('where jlink.exe 2^>nul') do (
    set "JLINK_EXE=%%i"
    goto :jlink_found
)

:jlink_found
if "%JLINK_EXE%"=="" (
    echo [MD3L] jlink.exe not found in PATH. Please install JDK 17 and add to PATH.
    exit /b 1
)

"%JLINK_EXE%" --add-modules java.base,java.desktop,java.management,java.naming,java.net.http,java.prefs,java.security.jgss,java.sql,jdk.crypto.ec,jdk.unsupported --strip-debug --no-man-pages --no-header-files --compress=2 --output "dist\runtime"
if errorlevel 1 (
    echo [MD3L] jlink runtime generation failed!
    exit /b 1
)

if not exist "dist\runtime\bin\java.exe" (
    echo [MD3L] Runtime generation failed: dist\runtime\bin\java.exe missing.
    exit /b 1
)

echo [MD3L] =======================================
echo [MD3L] Full Build Completed!
echo [MD3L] Zip dist\MD3L.exe and dist\runtime for initial release.
echo [MD3L] For updates, just run build-update.bat and upload MD3L.exe.
echo [MD3L] =======================================
