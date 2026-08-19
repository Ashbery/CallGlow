@echo off
setlocal enabledelayedexpansion
title CallGlow 一鍵安裝精靈
color 0B

echo.
echo  ============================================================
echo  =   CallGlow 一鍵安裝精靈                                =
echo  =   國行 OPPO Watch / ColorOS 手錶 的 LINE 來電通知      =
echo  ============================================================
echo.
echo   這個程式會自動完成 5 件事：
echo     [1] 偵測你的手機和手錶
echo     [2] 安裝手機端 APP
echo     [3] 安裝手錶端 APP
echo     [4] 授權 + 通知監聽 + ColorOS 防凍結白名單
echo     [5] 啟動手錶提醒服務
echo.
echo   依照畫面提示依序完成即可。
echo.
pause

rem ============ 1. adb 檢查 ============
where adb >nul 2>nul
if errorlevel 1 goto NOADB
goto HAVEADB

:NOADB
echo.
echo  [錯誤] 找不到 adb 工具（安裝 APP 需要它）
echo.
echo  解法二選一：
echo    A. 下載 platform-tools 解壓縮後，把這個 install.bat
echo       複製進 platform-tools 資料夾，再雙擊執行。
echo       下載：https://developer.android.com/tools/releases/platform-tools
echo    B. 或把 platform-tools 資料夾路徑加入系統 PATH 後重開本程式。
echo.
pause
exit /b 1

:HAVEADB
call adb start-server >nul 2>nul

rem ============ 2. 裝置偵測（可重試 5 次）============
set "PH=%~1"
set "WT=%~2"
if not defined PH goto SCAN
if not defined WT goto SCAN
goto CHECKARGS

:SCAN
set TRY=0
:SCANLOOP
set /a TRY+=1
echo.
echo  [..] 正在偵測裝置（第 !TRY! 次）……
set "PH="
set "WT="
set "PHM="
set "WTM="
for /f "tokens=1" %%d in ('call adb devices ^| findstr /r "device$"') do (
  for /f "delims=" %%m in ('call adb -s %%d shell getprop ro.product.model 2^>nul') do (
    echo     找到裝置 %%d = %%m
    echo %%m | findstr /i "OWW261" >nul
    if errorlevel 1 (
      set "PH=%%d"
      set "PHM=%%m"
    ) else (
      set "WT=%%d"
      set "WTM=%%m"
    )
  )
)
if defined PH if defined WT goto CONFIRM
echo.
echo  [錯誤] 沒有同時偵測到手機和手錶。請檢查：
echo.
echo   1. 手機和手錶都用 USB 線接到這台電腦
echo   2. 手機開啟 USB 偵錯：
echo      設定 → 關於手機 → 連點「版本號」7 次 →
echo      回到設定 → 開發者選項 → 開啟「USB 偵錯」
echo   3. 手錶開啟 USB 偵錯：
echo      手錶設定 → 關於 → 連點「版本號」7 次 →
echo      開發者選項 → 開啟「USB 偵錯」
echo   4. 裝置螢幕跳出「允許 USB 偵錯」→ 勾選一律允許 → 允許
echo.
if !TRY! geq 5 goto NODEV
echo  準備好後按任意鍵重新偵測（或直接關閉視窗取消）……
pause >nul
goto SCANLOOP

:NODEV
echo.
echo  [錯誤] 已嘗試 5 次仍找不到裝置。請確認 USB 線與偵錯設定後重開本程式。
echo.
pause
exit /b 1

:CONFIRM
echo.
echo  ============================================================
echo   偵測結果：
echo     手機：!PH!
if defined PHM echo     手機型號：!PHM!
echo     手錶：!WT!
if defined WTM echo     手錶型號：!WTM!
echo  ============================================================
echo.
choice /c YN /m "  正確嗎？[Y=繼續安裝，N=重新偵測]"
if errorlevel 2 goto SCAN
goto STARTINST

:CHECKARGS
call adb -s %PH% get-state 2>nul | findstr "device" >nul || (echo [錯誤] 手機 %PH% 未連上 & pause & exit /b 1)
for /f "delims=" %%m in ('call adb -s %PH% shell getprop ro.product.model 2^>nul') do set "PHM=%%m"
call adb -s %WT% get-state 2>nul | findstr "device" >nul || (echo [錯誤] 手錶 %WT% 未連上 & pause & exit /b 1)
for /f "delims=" %%m in ('call adb -s %WT% shell getprop ro.product.model 2^>nul') do set "WTM=%%m"

:STARTINST
echo.
echo  ============================================================
echo   [1/5] 安裝手機端 APP……
echo  ============================================================
call adb -s !PH! install -r "%~dp0CallGlow-Phone-v1.0.1.apk" || (echo [失敗] 手機 APK 安裝失敗 & pause & exit /b 1)
echo    [OK] 手機端已安裝
call adb -s !PH! shell settings put system adb_install_enabled 1 >nul 2>nul
call adb -s !PH! shell pm grant com.linewatch.phone android.permission.BLUETOOTH_SCAN >nul 2>nul
call adb -s !PH! shell pm grant com.linewatch.phone android.permission.BLUETOOTH_CONNECT >nul 2>nul
call adb -s !PH! shell pm grant com.linewatch.phone android.permission.POST_NOTIFICATIONS >nul 2>nul
echo    [OK] 藍牙/通知權限已授權

echo  ============================================================
echo   [2/5] 開啟通知監聽（讓 APP 能讀到 LINE 來電）……
echo  ============================================================
set "NL="
for /f "usebackq delims=" %%L in (`call adb -s !PH! shell settings get secure enabled_notification_listeners`) do set "NL=%%L"
echo !NL! | findstr /c:"com.linewatch.phone" >nul
if errorlevel 1 (
  if "!NL!"=="null" (set "NEWNL=com.linewatch.phone/.LineCallListenerService") else (set "NEWNL=!NL!,com.linewatch.phone/.LineCallListenerService")
  call adb -s !PH! shell settings put secure enabled_notification_listeners "!NEWNL!"
)
set "NL2="
for /f "usebackq delims=" %%L in (`call adb -s !PH! shell settings get secure enabled_notification_listeners`) do set "NL2=%%L"
echo !NL2! | findstr /c:"com.linewatch.phone" >nul
if errorlevel 1 (
  echo    [錯誤] 這支手機不允許自動開啟通知監聽（系統限制）
  echo    請手動開啟後按任意鍵繼續：
  echo    設定 → 通知與狀態列 → 通知監聽權限 → 勾選「LineWatch」
  pause >nul
) else (
  echo    [OK] 通知監聽已啟用
)

echo  ============================================================
echo   [3/5] 安裝手錶端 APP……
echo  ============================================================
call adb -s !WT! install -r "%~dp0CallGlow-Watch-v1.0.1.apk" || (echo [失敗] 手錶 APK 安裝失敗 & pause & exit /b 1)
echo    [OK] 手錶端已安裝

echo  ============================================================
echo   [4/5] 手錶授權 + ColorOS 防凍結白名單（防斷線，必做）……
echo  ============================================================
call adb -s !WT! shell appops set com.linewatch.watch SYSTEM_ALERT_WINDOW allow
call adb -s !WT! shell appops set com.linewatch.watch WRITE_SETTINGS allow
echo    [OK] 懸浮視窗/寫入設定已授權
set "AOD="
for /f "usebackq delims=" %%W in (`call adb -s !WT! shell settings get global aod_support_apps`) do set "AOD=%%W"
echo !AOD! | findstr /c:"com.linewatch.watch" >nul
if errorlevel 1 (
  if "!AOD!"=="null" (set "NEWAOD=com.linewatch.watch") else (set "NEWAOD=!AOD!,com.linewatch.watch")
  call adb -s !WT! shell settings put global aod_support_apps "!NEWAOD!"
)
set "AOD2="
for /f "usebackq delims=" %%W in (`call adb -s !WT! shell settings get global aod_support_apps`) do set "AOD2=%%W"
echo !AOD2! | findstr /c:"com.linewatch.watch" >nul
if errorlevel 1 (
  echo    [錯誤] 白名單寫入失敗。請稍後重開機再跑一次本程式。
  pause >nul
) else (
  echo    [OK] 白名單已加入
)

echo  ============================================================
echo   [5/5] 啟動手錶提醒服務……
echo  ============================================================
call adb -s !WT! shell am start -n com.linewatch.watch/.IncomingCallActivity --ez debug_end true >nul 2>nul
echo    [OK] 服務已啟動

echo  ============================================================
echo  =  全部完成！                                            =
echo  ============================================================
echo.
echo   接下來請用另一支電話打 LINE 語音/視訊給自己：
echo   來電 → 手錶持續震動 + 顯示來電者；掛斷 → 未接畫面。
echo.
echo   小提醒：
echo   - 手錶重開機後服務會自動啟動，不必再跑本程式。
echo   - 若手錶偶爾斷線，把手錶重開機一次即可自癒。
echo.
echo   按任意鍵關閉視窗。
pause >nul
exit /b 0
