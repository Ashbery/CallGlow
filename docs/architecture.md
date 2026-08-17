# 架構規格 v1.0

## 總覽
    手機端
    LINE → NotificationListenerService → CallParser → CallStateMachine ─┐
                            MainActivity（設定/測試）                     │
                            BleCentralService（FGS connectedDevice） ←───┘
                                             │ BLE（NUS，見 protocol.md）
    手錶端                                    │
                            BlePeripheralService（FGS, GattServer） ──► VibratorController
                                             └──► IncomingCallActivity（全螢幕）
                            Watchdog（120s）

## 手機端元件
1. NotificationListenerService
   - manifest: BIND_NOTIFICATION_LISTENER_SERVICE 權限 + 對應 intent-filter
   - 監聽 package：jp.naver.line.android（常數集中定義）
   - onNotificationPosted / onNotificationRemoved → CallParser
2. CallParser（純邏輯，JVM 單元測試）
   - 來電判定：category == Notification.CATEGORY_CALL 或 title/text 命中來電關鍵字
   - missed 判定：同 key 重新 posted 且文字含 未接／錯過／Missed
   - 名稱：title → subText → text 首行，去關鍵字，fallback 未知聯絡人
3. CallStateMachine：IDLE / RINGING；結束來源（removed / repost-missed / 90s watchdog）
4. BleCentralService（前台服務，type=connectedDevice）
   - 掃描（過濾 NUS UUID）→ autoConnect → requestMtu(247) → 寫 CHAR_CMD
   - 心跳 10s；重連 backoff 5s/15s/30s；重連後補送同步（見 protocol.md）
5. MainActivity 設定頁（依 ui-spec.md）：開關、狀態列、測試按鈕、權限跳轉

## 手錶端元件
1. BlePeripheralService（FGS）
   - openGattServer + advertise（ADVERTISE_MODE_LOW_LATENCY）
   - 收 CHAR_CMD → 狀態機 → 啟動/關閉 Activity + 震動；CHAR_STATE notify ack/pong
2. IncomingCallActivity
   - FLAG_ACTIVITY_NEW_TASK + KEEP_SCREEN_ON（finish 自動解除）
   - 來電：LINE 綠標題 + 名字大字 + 「震動中」指示
   - missed：標題改「LINE 未接來電」，8s 自動 finish
   - debug 深連結：adb shell am start -n com.linewatch.watch/.IncomingCallActivity --es name 測試 --ez missed false
3. VibratorController：vibrate(pattern,0) / cancel；重入保護；missed [300,200,300]
4. Watchdog：120s
5. BootReceiver：BOOT_COMPLETED → startForegroundService 重啟 BlePeripheralService
   （開機自啟，captain 裁決保留；ColorOS Watch 若攔截自啟動，使用者在手錶自啟動白名單允許）
6. SettingsActivity（選配，第 5 階段）：震動模式三檔

## 關鍵 Android 細節
- 手機（Android 16）：BLUETOOTH_SCAN／BLUETOOTH_CONNECT 執行期權限；
  FGS 必須宣告 FOREGROUND_SERVICE_CONNECTED_DEVICE；POST_NOTIFICATIONS 執行期權限
- 手錶（Android 11，無 GMS）：只用 AOSP API；BLUETOOTH + BLUETOOTH_ADMIN；
  禁用 androidx.wear 與 Google Play services
- 手錶背景啟動 Activity 可能受限：主路徑 FGS startActivity；
  備援路徑 SYSTEM_ALERT_WINDOW overlay（由 T1 探測確認，先保留設計）
- 兩端：minSdk 30、compileSdk 34、targetSdk 34

## 執行緒
- 所有 BLE 操作在 worker thread；UI 狀態回主執行緒
- 震動啟動/停止必須成對，避免重複啟動導致無法停止

## 專案設定
- phone-app/ 與 watch-app/ 各自獨立 Gradle 專案
- local.properties：sdk.dir=C:\\Users\\<你的使用者>\\AppData\\Local\\Android\\Sdk
- 無 gradle-wrapper.jar（本機無法生成二進位）→ 寫好 gradle/wrapper/gradle-wrapper.properties，
  使用者以 Android Studio 開啟時自動補齊
- 包名：com.linewatch.phone / com.linewatch.watch
