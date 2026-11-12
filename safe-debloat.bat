@echo off
adb get-state >nul 2>&1 || (echo No ADB device connected. & pause & exit /b 1)
echo Disabling only two optional Google apps for user 0...
adb shell pm disable-user --user 0 com.google.android.play.games
adb shell pm disable-user --user 0 com.google.android.feedback
echo Done. Du TV and Projectivy have not been touched.
adb shell pm list packages -d
pause
