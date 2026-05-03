# Wanderly Release Smoke Test Checklist
# Run from project root after: .\gradlew.bat :app:assembleRelease

param(
    [string]$ApkPath = "app\build\outputs\apk\release\app-release.apk",
    [string]$Package = "com.novahorizon.wanderly"
)

function Check($label, $cmd) {
    Write-Host "`n==> $label" -ForegroundColor Cyan
    try { Invoke-Expression $cmd; Write-Host "PASS" -ForegroundColor Green }
    catch { Write-Host "FAIL: $_" -ForegroundColor Red }
}

# 1. Signing
Check "APK signing" "apksigner verify --verbose $ApkPath | Select-String 'DOES VERIFY'"

# 2. Device
Check "Device connected" "adb devices | Select-String 'device$'"

# 3. Install
Check "Install release APK" "adb install -r $ApkPath"

# 4. Launch
Check "Launch app" "adb shell am start -n $Package/.SplashActivity"
Start-Sleep -Seconds 5

# 5. Logcat snapshot
adb logcat -d -t 300 | Out-File smoke-release-logcat.txt -Encoding utf8
$fatal = Select-String -Path smoke-release-logcat.txt -Pattern "FATAL EXCEPTION|ANR in $Package"
if ($fatal) {
    Write-Host "FATAL/ANR DETECTED:" -ForegroundColor Red
    $fatal | ForEach-Object { Write-Host $_.Line }
} else {
    Write-Host "No FATAL/ANR in logcat — PASS" -ForegroundColor Green
}

# 6. UI dump
adb shell uiautomator dump /sdcard/ui_smoke.xml 2>$null
adb pull /sdcard/ui_smoke.xml ui_smoke.xml 2>$null
if (Test-Path ui_smoke.xml) {
    $screen = Select-String -Path ui_smoke.xml -Pattern "Login|Sign in|Map|Splash|Auth"
    Write-Host "Screen detected: $($screen.Line.Substring(0, [Math]::Min(120, $screen.Line.Length)))"
} else {
    Write-Host "UIAutomator dump unavailable (null root node) — manual verification required" -ForegroundColor Yellow
}

Write-Host "`n--- MANUAL CHECKLIST (tick each after verifying on device) ---" -ForegroundColor Magenta
@(
    "[ ] App launches without crash",
    "[ ] Login / Register flow completes",
    "[ ] Onboarding shown on fresh install",
    "[ ] Map loads with location permission",
    "[ ] Mission generation works (Gemini proxy live)",
    "[ ] Hidden Gems loads (Places proxy live)",
    "[ ] Profile screen shows avatar / class / streak",
    "[ ] Username edit saves successfully (update_profile_username RPC)",
    "[ ] Social: send friend request from Account A",
    "[ ] Social: accept/reject from Account B",
    "[ ] No FATAL/ANR in logcat during any of the above",
    "[ ] Logout returns to Login screen"
) | ForEach-Object { Write-Host $_ }
