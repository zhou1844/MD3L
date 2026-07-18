@echo off
setlocal

REM Switch to script directory so relative paths work when double-clicked
cd /d "%~dp0"

echo [MD3L] Building installer...

if not exist "dist\MD3L.exe" (
    echo [MD3L] ERROR: dist\MD3L.exe not found. Run build-full.bat first.
    pause
    exit /b 1
)

if not exist "dist\runtime\bin\java.exe" (
    echo [MD3L] ERROR: dist\runtime\bin\java.exe not found. Run build-full.bat first.
    pause
    exit /b 1
)

REM Check for MD3LUpdater.exe (optional but recommended)
if not exist "dist\MD3LUpdater.exe" (
    echo [MD3L] WARNING: dist\MD3LUpdater.exe not found. Updates will fall back to PowerShell method.
    echo [MD3L] Run build-update.bat first to generate the updater.
)

REM Read version from insver.txt in project root
set "APP_VERSION="
set /p APP_VERSION=<insver.txt

if "%APP_VERSION%"=="" (
    echo [MD3L] WARNING: insver.txt not found or empty, using 1.0
    set "APP_VERSION=1.0"
) else (
    echo [MD3L] Version: %APP_VERSION%
)

REM Locate Inno Setup compiler
set "ISCC="
if exist "C:\Program Files (x86)\Inno Setup 6\ISCC.exe" set "ISCC=C:\Program Files (x86)\Inno Setup 6\ISCC.exe"
if exist "C:\Program Files\Inno Setup 6\ISCC.exe" set "ISCC=C:\Program Files\Inno Setup 6\ISCC.exe"
if not "%ISCC%"=="" goto :iscc_found
for /f "delims=" %%i in ('where ISCC.exe 2^>nul') do set "ISCC=%%i"
if not "%ISCC%"=="" goto :iscc_found

echo [MD3L] ERROR: Inno Setup not found. Download from https://jrsoftware.org/isdl.php
pause
exit /b 1

:iscc_found
echo [MD3L] Using: %ISCC%
"%ISCC%" /DAppVersion="%APP_VERSION%" "installer\MD3L_Setup.iss"
if errorlevel 1 (
    echo [MD3L] Build FAILED.
    pause
    exit /b 1
)

echo [MD3L] ========================================
echo [MD3L] Done! Version: %APP_VERSION%
echo [MD3L] Output: dist\MD3L_Setup.exe
echo [MD3L] ========================================
pause
