# 環境探測報告（T1 交付物）

> 維護者：integration-tester ｜ 版本：v1.1 完成（2026-08-17；全部探測項已回傳判讀。P7 由 T3 實測、P12 由 T2 安裝後執行）
> 用途：本文件既是探測指令清單，也是判讀手冊與最終報告。完整 dump 存於 repo probe/bt_phone.txt、probe/bt_watch.txt。
> 不可修改 protocol.md／ui-spec.md；探測若發現與定案衝突，先回報 captain。

## 0. 執行須知（給使用者）

1. 全程在 Windows「命令提示字元」(cmd) 執行；每段指令整段複製貼上即可（每段都很短）。
2. 若中文輸出亂碼，先在該視窗執行 `chcp 65001`。
3. 每段開頭 `set D=<序號>`：`<序號>` 換成該段標示的裝置序號（由 P0 取得）。**每開一個新的 cmd 視窗都要重設一次**。
4. 手機：USB 連線。手錶：Wi-Fi 偵錯連線（電腦與手錶需在同一網路）。
5. 回傳方式：把 cmd 原始輸出貼回對話並標註「P 幾」即可，不需整理。P3 的完整 dump 檔留在你本機（只貼摘要行）。

## 1. 探測總表（狀態：待執行 → 已回傳 → 已判讀）

| 編號 | 探測項 | 裝置 | 對應決策 | 狀態 |
|---|---|---|---|---|
| P0 | adb 連線與裝置序號 | 手機＋手錶 | 後續所有指令的 -s 目標 | 已回傳 ✅（手錶 %WT%、手機 %PH%，皆 USB/device） |
| P1 | CPU ABI | 手機＋手錶 | 兩端 APK abiFilters | 已回傳 ✅：手機 arm64-v8a；手錶⚠️armeabi-v7a(32-bit)→T3 保留 v7a |
| P2 | 藍牙／手錶 feature | 手機＋手錶 | BLE 可用性（D1） | 已回傳 ✅：兩台 bluetooth+le 齊全（手錶另含 type.watch） |
| P3 | dumpsys bluetooth_manager 完整存檔 | 手機＋手錶 | 手錶 profile 政策驗證（D1） | 已回傳 ✅：D1 複驗通過(無 RFCOMM/SPP)；手錶 GATT Server 有 heytap.accessory 運行先例 |
| P4 | oppo/heytap 系統套件 | 手機＋手錶 | 環境了解、BLE 並存風險 | 已回傳 ✅：手機僅 heytap.health(OHealth)；手錶 87 個系統套件 |
| P5 | 螢幕解析度／密度 | 手機＋手錶 | 圓形安全區 70% 換算 | 已回傳 ✅：手錶 466x466/320（70%≈163dp）；手機 1216x2688/520 |
| P6 | 系統語言 | 手機＋手錶 | UI 文案 | 已回傳 ✅：手錶 zh-CN；手機 zh-TW（使用者介面繁中） |
| P7 | 背景 Activity 啟動限制 | 手錶 | overlay 備援是否啟用 | 已回傳 ⚠️受限(Abort bg activity starts 10106) → 啟用 overlay 備援 |
| P8 | 藍牙開關狀態 | 手機＋手錶 | 前置條件 | 已回傳 ✅：兩台皆 1 |
| P9 | 電量／記憶體基線 | 手機＋手錶 | T5 耗電對照 | 已回傳 ✅：基線已錄（手機 55%/15.2GB、手錶 100%/1.87GB） |
| P10 | 系統版本資訊存檔 | 手機＋手錶 | 除錯依據 | 已回傳 ✅：兩台 fingerprint 完整（sdk 36/30、release 16/11） |
| P11 | LINE 套件與通知權限現況 | 手機 | T2 前置 | 已回傳 ✅：LINE 26.11.0；listeners 待 T2 補查 |
| P12 | 省電／自啟動政策 | 手機 | T2 安裝後／T5 前置 | T2 安裝後執行 |

## 2. 分段指令與判讀

### P0 連線與序號（裝置：兩台）
前置準備：
- 手機：設定 → 關於手機 → 連點「版本號」7 次 → 開發者選項 → 開啟 USB 偵錯；USB 接電腦選「傳輸檔案」；手機彈出「允許 USB 偵錯」選允許。
- 手錶：設定 → 關於手錶 → 連點版本號 7 次（路徑各版本略異）→ 開發者選項 → 開啟 ADB 偵錯／Wi-Fi 偵錯，記下畫面顯示的 IP（若為「無線偵錯」另記 IP:PORT 與配對碼）。

指令：
```
chcp 65001
adb devices -l
```
手錶連線（需要時，與電腦同一 Wi-Fi）：
```
adb connect <手錶IP:5555>
adb devices -l
```
若手錶走「無線偵錯」且有配對碼，先執行：
```
adb pair <手錶IP:PORT> <配對碼>
adb connect <手錶IP:5555>
```
預期輸出：兩列且狀態都是 `device`：
- 手機：`<序號>       device product:... model:... device:...`（紅魔 11 Pro+）
- 手錶：`192.168.x.x:5555       device product:... model:OWW261 ...`

判讀：
- 狀態 `device` → 正常；記下兩台序號供後續段落 `set D=` 使用。
- `unauthorized` → 裝置上允許除錯後重跑。
- `offline` → 手機重插線／手錶重開 Wi-Fi 偵錯。

結果（2026-08-17 完整回傳 ✅ P0 完成）：
- 手錶 ✅：序號 `%WT%`、狀態 device、model OWW261；以 **USB** 連線（非 Wi-Fi，後續手錶指令一律 `set WT=%WT%`）。
- 手機 ✅：序號 `%PH%`、狀態 device、model NX809J（紅魔 11 Pro+）；後續手機指令一律 `set PH=%PH%`。

### P0-1 手機補連步驟（已照此完成 ✅；留作疑難排除參考）
1. 手機接 USB 線（原廠線較穩）→ 通知列「USB 連線方式」選「傳輸檔案」(MTP)。
2. 設定 → 關於手機 → 連點「版本號」7 次 → 回設定根目錄 → 系統（或更多設定）→ 開發者選項 → 開啟「USB 偵錯」。
3. 手機彈出「允許 USB 偵錯？」勾選「一律允許」→ 允許。
4. 重跑 `adb devices -l`：應新增第二列 device（紅魔 11 Pro+）；其序號即為後續 `%PH%`。

### P1 CPU ABI（裝置：兩台；決定 APK abiFilters）
指令（每台各跑一次，`<序號>` 換成該台序號）：
```
set D=<序號>
adb -s %D% shell getprop ro.product.cpu.abi
adb -s %D% shell getprop ro.product.cpu.abilist
```
預期輸出：手機 `arm64-v8a`（abilist 含 arm64-v8a）；手錶預期也含 `arm64-v8a`（若為 `armeabi-v7a` 表示 32-bit 系統）。

判讀：
- 含 arm64-v8a → 兩端 APK 以 arm64-v8a 為準。
- 手錶僅 armeabi-v7a → 手錶 APK 需相容 armeabi-v7a；回報 captain 告知 T3。

結果（2026-08-17 完整回傳 ✅）：
- 手機 ✅：abi = arm64-v8a、abilist = arm64-v8a → 手機 APK 以 arm64-v8a 為準。
- 手錶 ⚠️：abi = armeabi-v7a、abilist = armeabi-v7a,armeabi → **32-bit 系統（非 arm64）**。手錶 APK 必須保留 armeabi-v7a（純 Kotlin 無原生庫時預設即含）；**禁止**在 gradle 設 abiFilters 排除 armeabi-v7a，否則 adb install 會報 INSTALL_FAILED_NO_MATCHING_ABIS。已通知 T3。

### P2 藍牙／手錶 feature（裝置：兩台；驗證 BLE 可用）
指令：
```
set D=<序號>
adb -s %D% shell pm list features | findstr /i "bluetooth watch"
```
預期輸出：
- 兩台都應有 `feature:android.hardware.bluetooth` 與 `feature:android.hardware.bluetooth_le`。
- 手錶另應有 `feature:android.hardware.type.watch`（確認手錶 form factor，UI 用）。

判讀：
- 有 bluetooth_le → BLE 可用（D1 定案成立）。
- 缺 bluetooth_le → 重大問題，立即回報 captain。

結果（2026-08-17 完整回傳 ✅）：
- 手機 ✅：android.hardware.bluetooth、android.hardware.bluetooth_le 均在；另有 android.hardware.bluetooth_le.channel_sounding（紅魔新機額外支援，無妨）。
- 手錶 ✅：android.hardware.bluetooth、android.hardware.bluetooth_le、android.hardware.type.watch 均在 → BLE 可用、手錶 form factor 確認。

### P3 dumpsys bluetooth_manager 完整存檔（裝置：兩台；D1 驗證）
指令（手機、手錶各跑一次；bt_phone.txt／bt_watch.txt 存本機保留）：
```
set D=<序號>
adb -s %D% shell dumpsys bluetooth_manager > bt_<裝置>.txt
findstr /i "profile" bt_<裝置>.txt
findstr /i "rfcomm spp" bt_<裝置>.txt
```
預期輸出與判讀：
- 手錶 profile 行：預期列出 A2DP／HEADSET／HID／PAN／PBAP／MAP／SAP／HEARING_AID（D1 已知結果）；`rfcomm spp` 應無輸出或無 SPP 項目 → 再次確認 RFCOMM 不可靠、BLE 定案正確。
- 手機：確認藍牙已啟用與各 profile 正常，作為 BLE 掃描可用性旁證。
- 請保留 bt_phone.txt／bt_watch.txt 完整檔；先貼兩行 findstr 的輸出，若需要更多段落我會再指定。

結果（2026-08-17 完整回傳 ✅；完整 dump 存於 repo probe/bt_phone.txt、probe/bt_watch.txt）：
- 手錶 ✅（D1 複驗通過）：Metadata profile 政策僅 A2DP／A2DP_SINK／HEADSET／HEADSET_CLIENT／HID_HOST／PAN／PBAP／PBAP_CLIENT／MAP／MAP_CLIENT／SAP／HEARING_AID，**無 RFCOMM/SPP** → BLE 定案再確認。GATT Server Map 顯示 com.heytap.accessory 已在跑 2 個 GATT Service（0xafaf、0xaa15）→ 手錶 BLE Peripheral（GattServer）路徑有實務運行先例。手錶名「OPP…15C」與 ui-spec 狀態列文字一致。手錶現以 HeadsetClient（HFP）與兩台手機連線（含本測試機）——經典藍牙與 BLE GATT 不同承載，並存不衝突。
- 手機 ✅：「Enabled Profile Services:」明細：GATT、A2DP、AVRCP、AVRCP_CONTROLLER、BATTERY、HEADSET、HEARING_AID、HID_DEVICE、HID_HOST、MAP、OPP、PAN、PBAP、SAP → GATT（BLE Client）啟用 ✅。Bonded devices 已有 OPPO Watch X3（裝置尾碼）與 OPPO Watch X3（<裝置尾碼>） 兩筆（系統層已配對；本專案 GATT 直連不需額外配對）。SCAN_MODE_CONNECTABLE 正常。

### P4 oppo/heytap 系統套件（裝置：兩台；環境了解）
指令：
```
set D=<序號>
adb -s %D% shell pm list packages | findstr /i "oppo heytap"
```
預期輸出與判讀：
- 手錶：預期有 com.heytap.*、com.oppo.* 系列（Heytap Health 配對／健康 app、系統元件）。用途：了解系統藍牙配套；BLE GATT 連線與其並存（LE 支援多連線），無衝突風險，僅記錄。
- 手機（紅魔）：預期幾乎無 oppo/heytap 套件（正常）。
- 若手錶出現明顯第三方藍牙佔用類 app，記錄之備查。

結果（2026-08-17 完整回傳 ✅）：
- 手機：僅 com.heytap.health（OHealth 手錶配套已裝，其 HeytapNotificationListenerService 已在 enabled_notification_listeners → 手機已會把 LINE 通知鏡像轉發手錶）。與本專案疊加不衝突；**T4 注意**：手錶可能同時出現系統通知鏡像與本 app 全螢幕畫面，驗收時記錄實際疊加情形。
- 手錶：87 個 com.heytap.*／com.oppo.* 套件（watchface／sports／health／dialer／powermanager／bluetooth.net.proxy 等），屬正常系統配套，記錄備查；其中 com.heytap.accessory 為 P3 所見 GATT Server 持有者。

### P5 螢幕解析度與密度（裝置：兩台；圓形 UI 換算）
指令：
```
set D=<序號>
adb -s %D% shell wm size
adb -s %D% shell wm density
```
預期輸出與判讀：
- 手錶預期 `Physical size: 466x466`、density 約 320 → dp 寬 = 466/(320/160) = 233dp；ui-spec 的 70% 安全區 ≈ 163dp 寬（文字最大寬、autosize 依此換算）。
- 手機為一般長方形螢幕（僅記錄）。
- 若手錶解析度或密度不同 → 用公式 dp = px / (density/160) 換算 70% 值並通知 T3；ui-spec 的 70% 規則不變。

結果（2026-08-17 完整回傳 ✅）：
- 手錶 ✅：Physical size 466x466、density 320 → dp 寬 233dp、70% 安全區 ≈ 163dp（與假設完全一致；T3 autosize 依此）。
- 手機 ✅（資訊性）：Physical size 1216x2688、density 520 → 一般長方形螢幕，僅記錄，不影響決策。

### P6 系統語言（裝置：兩台；UI 文案）
指令：
```
set D=<序號>
adb -s %D% shell getprop persist.sys.locale
adb -s %D% shell getprop ro.product.locale
```
預期輸出與判讀：
- 手錶中國版預期 zh-CN（或 zh-TW）；手機依使用者設定。
- UI 文案一律依 ui-spec 定案；語言探測僅供環境記錄與測試判讀（例如手錶系統字型缺字，T3 深連結測試可發現）。
- 若發現繁簡衝突等問題 → 回報 captain，不自行改 ui-spec。

結果（2026-08-17 完整回傳 ✅）：
- 手錶：persist.sys.locale 空、ro.product.locale = zh-CN（簡中系統）。UI 文案仍依 ui-spec 繁中字樣；Android 內建 NotoSansCJK 繁簡字形齊全，缺字風險低，以 T3 深連結畫面實測為準。
- 手機 ✅（資訊性）：persist.sys.locale = zh-TW、ro.product.locale = zh-CN → 使用者介面為繁中，僅記錄。

### P7 背景 Activity 啟動（BAL）限制（裝置：手錶；T3 實測設計）
背景：Android 11 對 targetSdk≥30 的 app 限制背景啟動 Activity；ColorOS Watch 16 可能有特化。手錶主路徑是 FGS 收到 BLE 指令後 startActivity；本探測確認該路徑在此手錶上是否被允許，決定是否啟用 overlay 備援（architecture.md 已預留）。**本段無法在 T3 前執行。**

T3 安裝 APK 後實測：
1. 主路徑（T4 整合一併覆蓋）：手機按「測試來電」→ 手錶 FGS 收到 start → startActivity。觀察手錶是否亮屏顯示來電畫面；同時看 logcat（tag LineWatchWatch）有無 `Background activity start`、`blocked`。
2. 隔離測試（建議 T3 在 debug 建置加 manifest receiver，action `com.linewatch.watch.action.TEST_BG_START`）：
```
set D=<手錶序號>
adb -s %D% shell am broadcast -a com.linewatch.watch.action.TEST_BG_START --es name 背景測試
```
   receiver 在背景呼叫 startActivity：畫面出現 → 背景啟動豁免有效；logcat 顯示 blocked → BAL 受限。
   （2026-08-17 追加：ColorOS Watch 擋 shell 廣播「Background execution not allowed」、--receiver-foreground 也擋 → 本隔離測試無法以 broadcast 觸發；主路徑實測證據已足夠，此步跳過。）
3. 若受限 → 備援路徑驗證：先執行 `adb -s %D% shell appops set com.linewatch.watch SYSTEM_ALERT_WINDOW allow`，改用 overlay 顯示（啟用前先回報 captain，因需選定架構備援分支實作）。

判讀決策：受限與否都寫回本節，並同步到 test-plan.md T4 前置。

結果（2026-08-17 T3 實測 ⚠️ **受限**）：
- 證據（logcat ActivityTaskManager）：「Background activity start [... isCallingUidForeground: false ... isBgStartWhitelisted: false]」「Abort background activity starts from 10106」→ 手錶背景啟動 Activity 被系統攔截。
- 決策與落地（2026-08-17 watch-engineer 完成，integration-tester 靜態驗證 ✅）：保留主路徑 FGS startActivity（螢幕亮時有效）；startActivity 拋例外 → 立即 overlay；被系統靜默 Abort → 1.5s 後 Activity 仍不可見改走 overlay（OverlayHelper 三態：來電／未接／斷線，未接與斷線 8s 自動關，震動仍由 service 管）；SCREEN_ON 亮屏時重試 Activity 並交還顯示（Activity onStart 關閉 overlay）。授權：`adb shell appops set com.linewatch.watch SYSTEM_ALERT_WINDOW allow`；使用者級「允許後台啟動」開關（若 ColorOS 選單有）可讓 Activity 路徑在背景也生效。TEST_BG_START 隔離 receiver 保留（重測預期仍 blocked_or_timeout，屬已知）。
- 影響：此錶背景（螢幕未亮）startActivity 會被 Abort → 顯示由 overlay 接手；T4 驗收以「螢幕未亮時 overlay 顯示、亮屏時 Activity 顯示」為準。

### P8 藍牙開關狀態（裝置：兩台；前置條件）
指令：
```
set D=<序號>
adb -s %D% shell settings get global bluetooth_on
```
預期輸出與判讀：`1` = 藍牙開（正常）；`0` = 關 → 請先在裝置上開啟藍牙（T4 測試時兩台都要開）。

結果（2026-08-17 完整回傳 ✅）：
- 手機 ✅：bluetooth_on = 1（藍牙已開）。
- 手錶 ✅：bluetooth_on = 1（藍牙已開）。

### P9 電量／記憶體基線（裝置：兩台；T5 對照用）
指令：
```
set D=<序號>
adb -s %D% shell dumpsys battery | findstr /i "level"
adb -s %D% shell cat /proc/meminfo | findstr /i "MemTotal MemAvailable"
```
預期輸出與判讀：記錄電量百分比與記憶體總量；T5 連續運行 2 小時前後對照（耗電、記憶體成長）。此為基線存檔，無通過／失敗。

結果（2026-08-17 完整回傳 ✅，T5 對照基線）：
- 手機：battery level 55；MemTotal 15600608 kB（≈15.2GB）、MemAvailable 7761912 kB。
- 手錶：battery level 100；MemTotal 1873636 kB（≈1.87GB）、MemAvailable 959580 kB。

### P10 系統版本資訊（裝置：兩台；除錯依據存檔）
指令：
```
set D=<序號>
adb -s %D% shell getprop ro.build.version.sdk & adb -s %D% shell getprop ro.build.version.release & adb -s %D% shell getprop ro.product.model & adb -s %D% shell getprop ro.build.fingerprint
```
預期輸出與判讀：手錶 sdk 30／release 11／model OWW261；手機 sdk 36／release 16。不一致 → 回報 captain（影響 minSdk/targetSdk 假設）。

結果（2026-08-17 完整回傳 ✅）：
- 手機 ✅：sdk 36／release 16；fingerprint: REDMAGIC/NX809J/NX809J:16/BQ2A.250705.001-BP2A.250605.031.A3/20260130.143527:user/release-keys。
- 手錶 ✅：sdk 30／release 11／model OWW261；fingerprint: OPPO/OWW261/OWW261:11/RKQ1.220916.001.11_A.188.260617214317/01:user/release-keys。與假設一致。

### P11 LINE 套件與通知權限現況（裝置：手機；T2 前置）
指令：
```
set D=<手機序號>
adb -s %D% shell pm list packages | findstr jp.naver.line.android
adb -s %D% shell dumpsys package jp.naver.line.android | findstr versionName
adb -s %D% shell settings get secure enabled_notification_listeners
```
預期輸出與判讀：
- 應有 `package:jp.naver.line.android`（LINE 已安裝）；記下版本號。
- enabled_notification_listeners 目前應為空或僅系統元件（本 app 尚未裝）→ T2 授權後再查一次，應含 `com.linewatch.phone/...`。
- 若無 LINE → 安裝並登入後再繼續 T4。

結果（2026-08-17 完整回傳 ✅）：
- LINE 已裝：package:jp.naver.line.android，versionName=26.11.0。
- enabled_notification_listeners 現有：com.zte.powersavemode/...、com.zte.mifavor.launcher/...、com.heytap.health/...HeytapNotificationListenerService（尚無 com.linewatch.phone → T2 安裝授權後補查，應新增）。

### P12 省電／自啟動政策（裝置：手機；T2 安裝後執行）
背景：紅魔系統省電管理可能殺背景服務，是 T5 穩定性前置。
指令（app 安裝後）：
```
set D=<手機序號>
adb -s %D% shell cmd deviceidle whitelist +com.linewatch.phone
adb -s %D% shell dumpsys deviceidle whitelist | findstr linewatch
```
手動設定（紅魔）：設定 → 電池 → 省電策略 → com.linewatch.phone 設「無限制」；安全中心 → 自啟動管理 → 允許；通知管理 → 允許通知。
判讀：whitelist 出現 com.linewatch.phone → 已加白名單；指令＋手動設定都完成才視為 T5 前置就緒。

結果：【T2 安裝後回填】

## 3. 已知情報與本次確認目標
- D1（2026-08-17）記載先前 ADB 探測：手錶藍牙 profile 政策僅 A2DP／HEADSET／HID／PAN／PBAP／MAP／SAP／HEARING_AID，無 SPP（RFCOMM）。**本次 P3 已正式存檔複驗通過**（probe/bt_watch.txt、probe/bt_phone.txt）。
- 本次探測已確認（2026-08-17，captain 直接執行 adb）：兩台 ABI（P1：手機 arm64-v8a、手錶 ⚠️armeabi-v7a 32-bit）、BLE feature（P2 兩台齊全）、profile 政策（P3）、圓形螢幕參數（P5：466x466/320）、語言（P6：手錶 zh-CN）、藍牙開關（P8）、基線（P9）、系統版本（P10）、LINE 與通知權限（P11）。BAL（P7）由 T3 實測；省電白名單（P12）由 T2 安裝後執行。
- 重要結論：手錶為 32-bit 系統 → T3 APK 必須含 armeabi-v7a；手錶 GATT Server 已有 com.heytap.accessory 運行先例；手錶系統層已與手機配對（OPPO Watch X3（<裝置尾碼>））。
- 追加發現（2026-08-17 T3 實測期，captain 直接執行）：
  - P7 實測：背景 Activity 啟動受限（Abort background activity starts from 10106）→ overlay 備援啟用。
  - ColorOS Watch 擋 shell 廣播（am broadcast 報 Background execution not allowed，--receiver-foreground 也擋）→ 所有手錶 debug 觸發一律改用 Activity 深連結。
  - 紅魔 ROM 全域 log.tag=S 靜默 app log → 手機端測試前需 `adb shell setprop log.tag.LineWatchPhone V`。
  - 手機安裝守衛 adb_install_enabled=0（已設 1）；日後 adb install 失敗先查 `settings get global adb_install_enabled`。
  - 手錶 BLE advertise errorCode=1（advertise data 超 31B）→ watch-engineer 修正中。

## 4. 判讀 → 決策對照總表
| 探測項 | 正常結論 | 異常時動作 |
|---|---|---|
| P1 手錶 32-bit（已確認 armeabi-v7a） | 手錶 APK 保留 armeabi-v7a（純 Kotlin 預設即含） | 禁止 abiFilters 排除 v7a；T3 建置後 adb install 驗證 |
| P2 缺 bluetooth_le | BLE 定案成立 | 立即回報 captain |
| P3 手錶出現 RFCOMM/SPP | 維持 BLE（D1） | 記錄，不影響定案 |
| P5 螢幕參數異於 466x466/320 | 70% 規則不變 | 換算後告知 T3 |
| P7 BAL 受限 | 主路徑 FGS startActivity | 啟用 overlay 備援（先回報 captain） |
| P8 bluetooth_on=0 | 前置就緒 | 先開藍牙 |
| P11 無 LINE | T4 前置 | 安裝並登入 LINE |
| P12 未加白名單 | T5 前置 | 完成手動設定 |

## 5. 回傳格式範例（使用者貼回對話）
```
P0 回傳：
<adb devices -l 的原始輸出>

P1 手機回傳：
<輸出>
P1 手錶回傳：
<輸出>
...
```
（captain 轉達後由 integration-tester 判讀回填。）
