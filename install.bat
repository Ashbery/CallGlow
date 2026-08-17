@echo off
setlocal enabledelayedexpansion

echo ==============================================
echo   CallGlow 一鍵安裝（手機 + 國行 ColorOS 手錶）
echo ==============================================
echo.

rem ---- 0. 前置檢查：adb ----
where adb >nul 2>nul
if errorlevel 1 goto NOADB
goto HAVEADB

:NOADB
echo [錯誤] 找不到 adb。請先安裝 platform-tools 並加入 PATH。
echo 下載：https://developer.android.com/tools/releases/platform-tools
pause
exit /b 1

:HAVEADB
rem ---- 1. 手動指定序號（可選）：install.bat 手機序號 手錶序號 ----
set "PH=%~1"
set "WT=%~2"

adb start-server >nul
echo 正在偵測裝置……
if not defined PH if not defined WT (
  for /f "tokens=1" %%d in ('adb devices ^| findstr /r "device$"') do (
    for /f "delims=" %%m in ('adb -s %%d shell getprop ro.product.model 2^>nul') do (
      echo   裝置 %%d = %%m
      echo %%m | findstr /i "OWW261" >nul && set "WT=%%d"
    )
    if not defined WT set "PH=%%d"
  )
)
if not defined PH (
  echo [錯誤] 找不到手機。請插上手機並開啟 USB 偵錯後重跑。
  pause
  exit /b 1
)
if not defined WT (
  echo [錯誤] 找不到手錶。請把手錶放上充電底座（USB）並開啟 USB 偵錯後重跑。
  pause
  exit /b 1
)
echo.
echo 手機序號：%PH%
echo 手錶序號：%WT%
echo.

rem ---- 2. 手機端 ----
echo [1/7] 安裝手機端 APK……
adb -s %PH% install -r "%~dp0CallGlow-Phone-v1.0.0.apk" || (echo [失敗] 手機 APK 安裝失敗 & pause & exit /b 1)
adb -s %PH% shell settings put system adb_install_enabled 1 >nul 2>nul
adb -s %PH% shell pm grant com.linewatch.phone android.permission.BLUETOOTH_SCAN >nul 2>nul
adb -s %PH% shell pm grant com.linewatch.phone android.permission.BLUETOOTH_CONNECT >nul 2>nul
adb -s %PH% shell pm grant com.linewatch.phone android.permission.POST_NOTIFICATIONS >nul 2>nul
echo [2/7] 啟用通知監聽……
set "NL="
for /f "usebackq delims=" %%L in (`adb -s %PH% shell settings get secure enabled_notification_listeners`) do set "NL=%%L"
echo !NL! | findstr /c:"com.linewatch.phone" >nul
if errorlevel 1 (
  if "!NL!"=="null" (set "NEWNL=com.linewatch.phone/.LineCallListenerService") else (set "NEWNL=!NL!,com.linewatch.phone/.LineCallListenerService")
  adb -s %PH% shell settings put secure enabled_notification_listeners "!NEWNL!"
  echo   [OK] 已啟用
) else (
  echo   [OK] 原本已啟用
)

rem ---- 3. 手錶端 ----
echo [3/7] 安裝手錶端 APK……
adb -s %WT% install -r "%~dp0CallGlow-Watch-v1.0.0.apk" || (echo [失敗] 手錶 APK 安裝失敗 & pause & exit /b 1)
echo [4/7] 授權懸浮視窗＋寫入設定……
adb -s %WT% shell appops set com.linewatch.watch SYSTEM_ALERT_WINDOW allow
adb -s %WT% shell appops set com.linewatch.watch WRITE_SETTINGS allow
echo [5/7] ColorOS 凍結防護白名單（防斷線，必做）……
set "AOD="
for /f "usebackq delims=" %%W in (`adb -s %WT% shell settings get global aod_support_apps`) do set "AOD=%%W"
echo !AOD! | findstr /c:"com.linewatch.watch" >nul
if errorlevel 1 (
  if "!AOD!"=="null" (set "NEWAOD=com.linewatch.watch") else (set "NEWAOD=!AOD!,com.linewatch.watch")
  adb -s %WT% shell settings put global aod_support_apps "!NEWAOD!"
  echo   [OK] 已加入白名單
) else (
  echo   [OK] 原本已在白名單
)

rem ---- 4. 啟動手錶服務 ----
echo [6/7] 啟動手錶提醒服務……
adb -s %WT% shell am start -n com.linewatch.watch/.IncomingCallActivity --ez debug_end true >nul 2>nul

echo [7/7] 完成！
echo.
echo ==============================================
echo  安裝完成。用另一支電話打 LINE 測試：來電 → 手錶震動顯示。
echo  若斷線頻繁：重開機一次讓 App 自癒白名單（aod_switch_apps）。
echo ==============================================
pause

