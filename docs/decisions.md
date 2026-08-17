# 決策紀錄（ADR）

## D1 BLE GATT 定案（Nordic UART Service）— 2026-08-17
ADB 探測：手錶藍牙 profile 政策僅 A2DP／HEADSET／HID／PAN／PBAP／MAP／SAP／HEARING_AID，
無 SPP（RFCOMM）項目 → RFCOMM 風險高。BLE 於 Android 11 + BT 5.0 必然可用。
定案：手機 = GATT Central，手錶 = GATT Peripheral；RFCOMM 僅留探測級 fallback。

## D2 未接來電顯示
收到 end(missed=true) → 手錶顯示「LINE 未接來電：名字」8 秒後自動關閉。
v1.1 補充（D11）：手機送完 end(false) 後武裝一次性 5s 未接判定窗，窗內收到未接通知 → 補送 t="missed"；
手錶 RINGING 中收到 → 視同 end(missed=true)；IDLE 收到 → 顯示未接畫面 8s（不震動）。

## D3 看門狗（雙保險）
手機端 90s：RINGING 後無結束事件 → 送 end(missed=true)。
手錶端 120s：收到 start 後未收到 end → 自動停止（視為 missed）。

## D4 震動
來電：pattern [600,400] 無限循環、幅度 150/255（約 59%，使用者回饋滿幅過強，2026-08-17 調降）；end → 立即 cancel()。
未接：短促 [300,200,300] 不循環、幅度同 150。重入保護：同一時間只有一組震動。

## D5 訊息格式
UTF-8 JSON、單次 write、≤200 bytes、name ≤60 bytes；MTU 247；心跳 10s；
連續 3 次無 pong → 重連（backoff 5s/15s/30s）。

## D6 手錶端禁用 GMS 依賴
純 AOSP API；不可使用 androidx.wear、Google Play services。

## D7 SDK 版本
兩端 minSdk 30、compileSdk 34、targetSdk 34（本機已裝 android-34）。

## D8 名稱解析
集中在手機端 CallParser（可設定化）；LINE 改版只改此處。

## D9 工作流程限制
本機 DSH shell 於 win32 不可用 → 使用者執行 adb／gradle／安裝；
Agent 產出程式碼＋指令清單＋判讀輸出。Android Studio 由使用者安裝（zip 版免管理員）。

## D12 頭像傳輸協議 v2（T8）— 2026-08-17（captain 批准）
LINE 通知 largeIcon 實測帶頭像（156×156，免 READ_CONTACTS）→ av_start/av_chunk/av_end 分塊傳輸
（339B/塊、b64、SHA-256 驗證、≤3s、不阻塞 start/end）。v1 訊息 ≤200B 規則僅適用 v1；
av_chunk 放寬 500B。詳見 protocol.md v2 章節。

## D11 未接判定窗與 t="missed" 補送（方案 B）— 2026-08-17（captain 批准，T4 實測驅動）
實測：LINE 未接通知於 removed→end(false) 之後到達（或置於 subText，D8 解析已納入），
且接聽場景必須立即停震。定案：end(false) 立即送出（接聽/拒接不受影響）＋一次性 5s 未接判定窗
→ 補送 t="missed"，手錶 IDLE 顯示未接畫面 8s 不震動。詳細見 protocol.md v1.1。

## D10 手錶端開機自啟（BootReceiver）— 2026-08-17（captain 裁決）
手錶 App 無 launcher Activity → 無開機自啟則每次重開機須手動 adb 起服務，T5.7 重開機恢復必敗。
定案：保留 BootReceiver（BOOT_COMPLETED → startForegroundService 重啟 BlePeripheralService）；
Android 11 允許開機廣播啟動 FGS（無 12+ 的背景啟動限制）。
中國版 ColorOS Watch 自啟動白名單可能攔截 → 使用者於手錶設定允許自啟動／後台運行
（路徑以實機為準，見 watch-app/README.md）。

## D13 斷線根因：ColorOS Watch 凍結第三方 App（BmPowerManager）— 2026-08-17（captain 實測裁決）
實測證據：連線後 ~2 分鐘 Watch App 被凍結（log 靜默、GATT 無回應）→ 手機 4–16 分鐘後才重連；
WearFrw AlarmController「Forbid delivering pending non wakeup alarm」封鎖第三方 AlarmManager 喚醒。
修法（實測有效）：把 com.linewatch.watch 加入 watch 端 aod_support_apps / aod_switch_apps /
aod_switch_all_apps＋deviceidle whitelist → 螢幕關閉 8+ 分鐘 ping/pong 不中斷；
手機藍牙開關後 10 秒重連。v3.20 起 BootReceiver/服務 onCreate 自癒：
aod_switch_apps 可由 App 自寫（WRITE_SETTINGS appop）；aod_support_apps/aod_switch_all_apps 為受保護鍵
（第三方寫入必被拒，即使 pm grant WRITE_SECURE_SETTINGS 亦然）→ 由 adb 一次性寫入（跨重啟持久）；
開機時系統會重建 aod_switch_all_apps，但實測防凍結只需 support+switch 兩鍵。
最終驗證（2026-08-17 晚，重啟後）：三鍵 adb 寫入＋deviceidle 白名單 → 螢幕關閉 7.5–8+ 分鐘
ping/pong 不中斷、無凍結動作；重啟後服務自動起＋自動重連。App 自寫 aod_switch_apps 視
SettingsProvider 而定（有時要求 WRITE_SECURE_SETTINGS 而拒）→ 以 adb 一次性寫入為準，README 已註明。

## D14 不做「手機端斷線來電強提醒」兜底 — 2026-08-17（使用者裁決）
使用者明確表示不需要（「這兩個都不用」）→ 不實作手機端 BLE 斷線時的強震＋響鈴兜底。
若日後需要再開票。

## D15 通知畫面右滑關閉＋抬腕只顯示一次＋震動持續到接通/掛斷 — 2026-08-17（使用者裁決）
實測回饋三點：
1. 未接/斷線畫面原本 8s 自動關 → 改**不自動關閉**，由使用者**右滑關閉**（Activity 與 overlay 皆已有右滑手勢）；
   兩態清除 FLAG_KEEP_SCREEN_ON，允許手錶自然息屏。
2. 抬腕顯示只一次：CALLING 中螢幕息屏 → 發 ACTION_HIDE_UI 結束來電畫面（**不結束通話**），
   之後再抬腕不再重顯示（callUiHiddenByScreenOff 旗標，新通來電重置）。
3. 震動持續到接通/掛斷：ColorOS 息屏會取消第三方 haptic → SCREEN_OFF 立即重掛＋5s 週期重掛
   （VibratorController.rearmCall；自訂節奏重發同一波形，系統效果模式本有 700ms 循環自動續發）；
   SCREEN_ON 亦重掛一次。

### D15 修正（使用者第二次回饋，同日）
1. 未接/斷線畫面：**不是**一直顯示——「看過一次」原則：息屏即隱藏（HIDE_UI）、再抬腕不重顯示；
   若螢幕持續亮著未熄 → **8s 自動關閉**或右滑提前關（兩者皆保留）。
   未接畫面**不顯示**「右滑關閉」提示文字。
2. 來電中（未接通）邏輯維持 D15 原版（息屏隱藏畫面＋震動重掛），使用者確認正確。
3. 實作修正：SCREEN_OFF 廣播到達時 Activity.onStop 已先把 isVisible 清掉 → 服務端不再檢查
   isVisible（displayState 非空即發 HIDE_UI）；Activity 的 stateReceiver 改在 onCreate 註冊、
   onDestroy 解除（舊寫法 onStop 解除 → 息屏時收不到 HIDE_UI）。

## D16 通知字體 Yomogi＋名字縮小 — 2026-08-17（使用者裁決）
所有通知文字（來電/未接/斷線的標題、名字、副標、首字頭像）統一 **Yomogi** 手寫感日系字體
（google/fonts，SIL OFL 1.1；res/font/yomogi_regular.ttf，授權檔 watch-app/LICENSE-Yomogi.txt；
Fonts 物件以 resources.getFont 載入，API 26+ 原生，無 androidx 依賴）。
名字最大字號 40sp → 34sp → **28sp**（D16 二修：長英文名不出界；Activity 與 overlay 同步，
autosize 12–28sp 自動縮放至屏寬 70% 內，min 12sp 可容納約 20 字元英文名）。

## D17 銀河主題來電特效 — 2026-08-17（使用者裁決）
使用者選 A＋C＋F 且要求光環「帥一點、太空銀河感」。定案：
- A 極光：AuroraView 改寫為三團銀河柔光（深空紫/星雲青/星雲洋紅）30s 緩轉。
- C 光環隨震動節拍明滅（亮相/熄滅與所選震動節奏同步）＋sweep 銀河漸層＋30s 正/反自轉；
  漣漪三圈改染紫/青/洋紅。
- F 邊緣呼吸光環：新 EdgeHaloView（圓屏外圈 3dp 細環、sweep 漸層、3s 呼吸＋45s 自轉）。
- 配色 palette 新增 colors.xml（galaxy_* 五色）；LINE 綠僅留副標點綴。
- 長名字（12sp 仍超 70% 寬）→ 固定 16sp＋marquee 橫向滾動（Activity/overlay）。
- 僅 CALLING 套用；MISSED 橙、DISCONNECTED 灰不變。
