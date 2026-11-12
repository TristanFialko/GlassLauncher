@echo off
if "%~1"=="" (echo Drag app-debug.apk onto this file, or run install-to-box.bat path\to\app-debug.apk & pause & exit /b 1)
adb get-state >nul 2>&1 || (echo No ADB device connected. & pause & exit /b 1)
adb install -r "%~1"
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
echo If Android shows a Home app chooser, choose Glass Launcher and select Always.
pause
