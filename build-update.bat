@echo off
chcp 65001 >nul
setlocal

echo [MD3L] Building portable EXE...
echo [MD3L] Stopping stale Gradle daemons...
call gradlew.bat --stop >nul 2>nul

echo [MD3L] Running Gradle build (no daemon)...
call gradlew.bat packageReleaseUberJarForCurrentOS --no-daemon --console=plain
if %ERRORLEVEL% neq 0 (
    echo Gradle packageReleaseUberJarForCurrentOS failed!
    pause
    exit /b %ERRORLEVEL%
)

echo [MD3L] Finding generated jar...
set APP_JAR=
for /f "delims=" %%i in ('dir /b /a-d /s "build\compose\jars\*-windows-x64-*.jar"') do set APP_JAR=%%i

if "%APP_JAR%"=="" (
    echo [MD3L] Jar not found!
    pause
    exit /b 1
)

echo [MD3L] Compiling C# Bootstrapper...
C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe /nologo /target:winexe /win32icon:src\main\resources\app_icon.ico /win32manifest:scripts\app.manifest /out:scripts\stub.exe scripts\LauncherStub.cs
if %ERRORLEVEL% neq 0 (
    echo Stub compilation failed!
    pause
    exit /b %ERRORLEVEL%
)

if not exist dist mkdir dist

echo [MD3L] Merging EXE and JAR...
copy /b "scripts\stub.exe" + "%APP_JAR%" "dist\MD3L.exe" >nul

echo [MD3L] Cleanup...
del "scripts\stub.exe"

echo [MD3L] Compiling MD3LUpdater (?????)...
C:\Windows\Microsoft.NET\Framework64\v4.0.30319\csc.exe /nologo /target:winexe /win32icon:src\main\resources\app_icon.ico /win32manifest:updater\updater.manifest /out:dist\MD3LUpdater.exe updater\MD3LUpdater.cs
if %ERRORLEVEL% neq 0 (
    echo [MD3L] WARNING: MD3LUpdater compilation failed! Updates will fall back to PowerShell method.
) else (
    echo [MD3L] MD3LUpdater compiled successfully: dist\MD3LUpdater.exe
)

echo [MD3L] Done! Output: dist\MD3L.exe  +  dist\MD3LUpdater.exe
pause
