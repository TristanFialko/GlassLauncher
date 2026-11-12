@echo off
adb get-state >nul 2>&1 || (echo No ADB device connected. & pause & exit /b 1)
adb shell pm enable com.google.android.play.games
adb shell pm enable com.google.android.feedback
echo Restored.
pause
