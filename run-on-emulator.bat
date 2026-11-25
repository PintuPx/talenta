@echo off
rem Builds Talenta, starts the Android emulator in a window and opens the app.
rem Double-click this file, or run it from a terminal. First start takes about 1 minute.
setlocal
if not defined TOOLS set TOOLS=%USERPROFILE%\android-toolchain
set JAVA_HOME=%TOOLS%\jdk17
set ANDROID_SDK_ROOT=%TOOLS%\sdk
set ANDROID_AVD_HOME=%TOOLS%\avd
set ADB=%ANDROID_SDK_ROOT%\platform-tools\adb.exe

cd /d "%~dp0"
echo Building the app...
call gradlew.bat :app:assembleDebug -q || goto :error

"%ADB%" get-state 1>nul 2>nul
if errorlevel 1 (
  echo Starting the emulator window...
  start "" "%ANDROID_SDK_ROOT%\emulator\emulator.exe" -avd budget36 -no-audio -no-snapshot-save
)
echo Waiting for the emulator to boot...
"%ADB%" wait-for-device
:waitboot
for /f %%b in ('"%ADB%" shell getprop sys.boot_completed 2^>nul') do set BOOT=%%b
if not "%BOOT%"=="1" ( timeout /t 2 /nobreak >nul & goto :waitboot )

echo Installing and opening Talenta...
"%ADB%" install -r app\build\outputs\apk\debug\app-debug.apk || goto :error
"%ADB%" shell am start -n com.talenta.finance.debug/com.homebudget.app.MainActivity
echo Done. Close the emulator window when you are finished.
exit /b 0

:error
echo Something went wrong (see messages above).
pause
exit /b 1
