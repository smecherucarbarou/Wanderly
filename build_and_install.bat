@echo off
setlocal enabledelayedexpansion

echo ============================================
echo   WANDERLY - Build ^& Install
echo ============================================
echo.

:: Menu
echo  [1] Debug APK
echo  [2] Release APK (signed)
echo  [3] Both (debug + release)
echo.
set /p BUILD_CHOICE="Alege varianta (1/2/3): "

if "%BUILD_CHOICE%"=="1" (
    set BUILD_DEBUG=1
    set BUILD_RELEASE=0
) else if "%BUILD_CHOICE%"=="2" (
    set BUILD_DEBUG=0
    set BUILD_RELEASE=1
) else if "%BUILD_CHOICE%"=="3" (
    set BUILD_DEBUG=1
    set BUILD_RELEASE=1
) else (
    echo Optiune invalida.
    goto :eof
)

:: Build
echo.
echo [BUILD] Compilare in curs...
echo.

if "%BUILD_DEBUG%"=="1" if "%BUILD_RELEASE%"=="1" (
    call gradlew.bat assembleDebug assembleRelease --console=plain
) else if "%BUILD_DEBUG%"=="1" (
    call gradlew.bat assembleDebug --console=plain
) else (
    call gradlew.bat assembleRelease --console=plain
)

if errorlevel 1 (
    echo.
    echo [EROARE] Build-ul a esuat!
    pause
    goto :eof
)

echo.
echo [OK] Build reusit!
echo.

:: Detect APK paths
set "DEBUG_APK=app\build\outputs\apk\debug\app-debug.apk"
set "RELEASE_APK=app\build\outputs\apk\release\app-release.apk"

:: Choose which APK to install
if "%BUILD_DEBUG%"=="1" if "%BUILD_RELEASE%"=="1" (
    echo  [1] Instaleaza Debug APK
    echo  [2] Instaleaza Release APK
    echo  [3] Nu instala
    echo.
    set /p INSTALL_CHOICE="Ce instalez? (1/2/3): "
) else if "%BUILD_DEBUG%"=="1" (
    set INSTALL_CHOICE=1
) else (
    set INSTALL_CHOICE=2
)

if "%INSTALL_CHOICE%"=="3" (
    echo.
    echo Done. APK-urile sunt in:
    if "%BUILD_DEBUG%"=="1" echo   - %DEBUG_APK%
    if "%BUILD_RELEASE%"=="1" echo   - %RELEASE_APK%
    pause
    goto :eof
)

if "%INSTALL_CHOICE%"=="1" (
    set "APK_PATH=%DEBUG_APK%"
    set "APK_NAME=Debug"
) else (
    set "APK_PATH=%RELEASE_APK%"
    set "APK_NAME=Release"
)

if not exist "%APK_PATH%" (
    echo [EROARE] APK-ul nu exista: %APK_PATH%
    pause
    goto :eof
)

:: List ADB devices
echo.
echo [ADB] Dispozitive conectate:
echo.

set DEVICE_COUNT=0
for /f "skip=1 tokens=1,2" %%a in ('adb devices') do (
    if "%%b"=="device" (
        set /a DEVICE_COUNT+=1
        set "DEVICE_!DEVICE_COUNT!=%%a"
        echo   [!DEVICE_COUNT!] %%a
    )
)

if %DEVICE_COUNT%==0 (
    echo.
    echo [EROARE] Niciun dispozitiv conectat! Verifica USB debugging.
    pause
    goto :eof
)

:: Select device
if %DEVICE_COUNT%==1 (
    set "SELECTED_DEVICE=!DEVICE_1!"
    echo.
    echo   Selectat automat: !SELECTED_DEVICE!
) else (
    echo.
    set /p DEV_CHOICE="Alege dispozitivul (1-%DEVICE_COUNT%): "
    set "SELECTED_DEVICE=!DEVICE_!DEV_CHOICE!!"
    if "!SELECTED_DEVICE!"=="" (
        echo [EROARE] Selectie invalida.
        pause
        goto :eof
    )
)

:: Install
echo.
echo [INSTALL] Instalez %APK_NAME% pe %SELECTED_DEVICE%...
echo.

adb -s %SELECTED_DEVICE% install -r "%APK_PATH%"

if errorlevel 1 (
    echo.
    echo [EROARE] Instalarea a esuat!
    pause
    goto :eof
)

echo.
echo [OK] %APK_NAME% instalat cu succes pe %SELECTED_DEVICE%!
echo.

:: Launch app
set /p LAUNCH_APP="Pornesc aplicatia? (y/n): "
if /i "%LAUNCH_APP%"=="y" (
    adb -s %SELECTED_DEVICE% shell am start -n com.novahorizon.wanderly/.SplashActivity
    echo [OK] Aplicatia pornita!
)

echo.
pause
