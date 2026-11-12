@echo off
rem Runs the Glass Launcher device benchmark (see bench-device.ps1 for options). Requires adb on PATH.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0bench-device.ps1" %*
