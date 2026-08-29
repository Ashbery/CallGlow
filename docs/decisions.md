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

### D15 再修正（使用者實機二次測試，同日）——CALLING 與通知類分開
- CALLING（對方未掛斷）：息屏**不**隱藏來電畫面，每次抬腕都重新顯示，震動持續到接通/掛斷。
- MISSED/DISCONNECTED（通知類）：維持「顯示一次」——息屏即隱藏、抬腕不再重顯。
- 系統通知列衝突：抬腕時 ColorOS 會優先顯示系統通知列，第三方 App 無法搶奪該優先權
  （系統層行為）；我們的 Activity 於 SCREEN_ON 後重新啟動、以 showWhenLocked 蓋上，
  來電中使用者仍會看到來電畫面。

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
- F 邊緣星海：EdgeHaloView——D17.2 使用者再次回饋「線條光環單調，可以是一圈星海」→
  移除全部線條，改環帶星海（星雲底霧＋微星 110＋亮星 16 十字星芒，48s 公轉）。
- 配色 palette 新增 colors.xml（galaxy_* 五色）；LINE 綠僅留副標點綴。
- 長名字（12sp 仍超 70% 寬）→ 固定 16sp＋marquee 橫向滾動（Activity/overlay）。
- 僅 CALLING 套用；MISSED 橙、DISCONNECTED 灰不變。

## D18 正式版日誌策略 — 2026-08-18（補登）
正式版關閉常態日誌（LOG_ENABLED=false，Log.w/Log.e 不受控）。

## D19 頭像傳輸健壯性＋快取容量制 — 2026-08-18（使用者實測回饋：第 3~4 位來電者頭貼偶發未到手錶）
1. 根因：頭像 session 在 BLE 未就緒時被直接丟棄（start 指令有 onBleReady 補送，頭像沒有）
   → 手錶整通只顯示首字。修復：未就緒改為 500ms 輪詢等就緒（上限 15s）再自動重送；
   av_fail 重試一次維持不變；finishAvatarSession 一併取消輪詢。
2. 快取上限由「≤10 個名字」改為「容量制 ≤1MB（每張 ≤12KB，≈80+ 人）＋索引 ≤256 筆安全上限」；
   格式不變（files/avatars/<sha16(name)>.jpg＋JSON 索引），既有快取相容；同名字覆寫同一格。
3. 日誌改執行期開關（Log.isLoggable）：adb shell setprop log.tag.LineWatchWatch V ／
   log.tag.LineWatchPhone V 即啟用，免重裝；預設仍全關（隱私不變）。
4. （v1.0.1b）接聽／掛斷／未接**不再中止頭像傳輸**（手機端 sendEnd／missed 補送、手錶端
   endCall／IDLE-missed 皆移除 abort）→ 快速接聽時頭像仍自然完成並寫入快取，下一通同人秒顯；
   新 av_start／5s 逾時／BLE 斷線仍負責清理半截 session。

## D20 頭像備援快取＋裝置端檔案日誌＋快取 500MB — 2026-08-18（使用者裁決）
1. 快取上限由 1MB 改為 **500MB**（使用者明示；每張 ≤12KB ≈ 4 萬人，實務上等同不會淘汰）；
   索引安全上限 50,000 筆防病態。
2. 手機端新增「上次成功頭像」備援快取（filesDir/avatar_last/<sha16(name)>.jpg，LRU 20）：
   LINE 通知當次未附 largeIcon/pic 時，自動以該名字上次成功送出的 JPEG 補送（鍵＝截斷後名字，
   與手錶端一致）→ 解決「整天讀不到頭像、隔天恢復」的間歇問題（源頭是 LINE 端附圖不穩定）。
3. 裝置端檔案日誌（watch/phone 皆新增 LogFile）：<externalFilesDir>/logs/yyyyMMdd.log，
   一律寫入（不受 setprop 開關影響），保留 3 天＋總量 8MB 安全上限，逾齡/超量自動刪最舊；
   adb pull /sdcard/Android/data/<pkg>/files/logs/ 可取回。

## D21 Pulsar 震動模式庫＋手勢修正 — 2026-08-29（使用者指定）
1. 震動模式：原「系統預定義效果 4 種」（v3.17）由 **Pulsar（com.swmansion:pulsar 1.3.0，MIT）15 種預設**取代
   （alarm/buzz/clamor/charge/crescendo/bassDrop/canter/cadence/chime/bellToll/barrage/catPaw/dewdrop/cascade/explosion）；
   Settings「震動模式」16 選項（含自訂節奏）；來電循環＝預設時長＋150ms 重發；重掛/預覽即時生效。
2. D6「純 AOSP」部分鬆綁：pulsar 傳遞依賴 androidx.core/appcompat/material/kotlinx-serialization
   → 啟用 android.useAndroidX、compileSdk 36、AGP 8.9.1、Gradle 8.13（watch 端）；APK 4.86MB→14.4MB。
   仍無 GMS、無 androidx.wear。
3. 服務端 Context 適配：Pulsar.getPresets() 內部以「context as Activity」建立 ActivityProvider →
   子類別 WatchPulsar 覆寫 createPresets() 用可空 ActivityProvider（view-based presets 不需 Activity）。
4. 手勢修正：右滑關閉改「嚴格橫向主導」（跟手門檻 dx≥40 且 |dy|≤dx；關閉門檻 dx≥80 且 |dy|≤dx×0.3），
   螢幕中間往下滑不再誤觸關閉（Activity/Overlay/Settings 三處同步）。
