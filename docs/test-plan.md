# 測試計畫 v2.0（完整版）

> 維護者：integration-tester。範圍 T1~T5，所有指令在 Windows cmd 執行；裝置序號以 %PH%（手機）／%WT%（手錶）取代（取得方式見 docs/probe-report.md P0）。
> 各階段驗收以本文件為準。測試記錄回填「附錄 A 測試記錄表」。
> 規格來源：protocol.md／architecture.md／ui-spec.md／decisions.md（定案內容，本文件不修改）。

## 0. 通用約定
- **logcat tag 慣例（T2/T3 必須遵循）**：手機端 `LineWatchPhone`、手錶端 `LineWatchWatch`；關鍵事件（start／end／ping／pong／連線／重連／看門狗）輸出完整 JSON。
- 雙端同時收 log：開兩個 cmd 視窗，`adb -s %PH% logcat -s LineWatchPhone:* > phone.log` 與 `adb -s %WT% logcat -s LineWatchWatch:* > watch.log`；每輪測試後檢查檔案尾部。
- 多裝置：所有指令帶 -s 明確指定目標。
- 「測試來電／測試未接／停止測試」按鈕為免真實通話的主要測試手段（ui-spec 定案）。
- 紅魔 ROM 全域 log.tag=S 會靜默 app log → **手機端測試前先** `adb -s %PH% shell setprop log.tag.LineWatchPhone V`（重開機後需重設）。
- adb install 失敗先查安裝守衛：`adb -s %PH% shell settings get global adb_install_enabled`（紅魔曾預設 0，已設 1）。
- ColorOS Watch 擋 shell 廣播（am broadcast 一律 Background execution not allowed，--receiver-foreground 也擋）→ **手錶 debug 觸發一律用 Activity 深連結，禁用 broadcast**。

## 1. T1 環境探測（integration-tester 主導；captain 以 adb 執行）
- 內容與指令：docs/probe-report.md P0~P12。
- 流程：逐段執行 → integration-tester 判讀回填 → 全數「已判讀」才算完成。
- 驗收：probe-report.md 第 1 節總表全部「已判讀」；關鍵結論（ABI、BLE feature、profile 政策、螢幕參數、語言）已回填；P7 若標註 T3 實測，須在 T3 完成後回填。
- ✅ 2026-08-17 T1 完成：全項已回傳判讀（P0~P6、P8~P11）；P7 由 T3 實測回填、P12 由 T2 安裝後回填。完整 dump 存 probe/bt_phone.txt、probe/bt_watch.txt。

## 2. T2 手機端（phone-engineer 開發；使用者建置）
### 2.1 建置
1. Android Studio 開啟 phone-app/（首次自動下載 Gradle，依 gradle-wrapper.properties 補齊 wrapper）。
2. 等 sync 完成 → Build → Build APK → 輸出 app-debug.apk。
3. 驗收：Build 成功無 error。
### 2.2 安裝與權限
```
set PH=<手機序號>
adb -s %PH% install -r <apk路徑>
adb -s %PH% shell pm grant com.linewatch.phone android.permission.BLUETOOTH_SCAN
adb -s %PH% shell pm grant com.linewatch.phone android.permission.BLUETOOTH_CONNECT
adb -s %PH% shell pm grant com.linewatch.phone android.permission.POST_NOTIFICATIONS
adb -s %PH% shell cmd notification allow_listener com.linewatch.phone/.NotificationListenerService
adb -s %PH% shell settings get secure enabled_notification_listeners
```
（NotificationListenerService 完整類別名以 T2 實作檔為準；最後一行應含 com.linewatch.phone。若 allow_listener 失敗：手機設定 → 通知與狀態列 → 通知使用權 → 允許本 app。）
備註：adb install 失敗時先查 `adb -s %PH% shell settings get global adb_install_enabled`（紅魔曾預設 0，已設 1）。
驗收：三項權限授予成功、通知使用權開通。
### 2.3 單元測試（CallParser）
- Android Studio 執行 phone-app 的 test（CallParserTest 等）→ 全綠。
- 驗收：來電判定、missed 判定、名稱解析（title→subText→text 首行）、關鍵字過濾、fallback「未知聯絡人」等用例全過。
### 2.4 功能冒煙（無手錶亦可）
0. ⚠️ 前置：紅魔全域 log.tag=S 靜默 app log → 先執行 `adb -s %PH% shell setprop log.tag.LineWatchPhone V`（重開機後需重設），否則看不到 LineWatchPhone log。
1. 開啟 app：狀態列顯示「藍牙：掃描中／已斷線」。
2. 開總開關 → 狀態列變化、logcat（LineWatchPhone）出現掃描／連線嘗試（NUS UUID 過濾）。
3. 按「測試來電」→ logcat 出現 `{"t":"start",...}` 寫入嘗試（無手錶則寫入失敗＋重連 backoff 5s/15s/30s 順序）。
4. 按「停止測試」→ logcat 出現 `{"t":"end",...}`。
5. 按「測試未接」→ `{"t":"end","missed":true}`。
- 驗收：logcat 事件序列正確、無 crash。
### 2.5 前置完成項
- 確認 LINE 已裝（probe-report.md P11）；完成省電白名單與自啟動（P12）。

## 3. T3 手錶端（watch-engineer 開發；使用者建置）
> 實測進度（2026-08-17，captain 直接執行）：來電畫面＋震動 ✅、未接 8s 自動關 ✅、120s 看門狗 ✅、GattServer ✅、FGS ✅；BAL 受限（P7）→ overlay 備援接入中；待修：BLE advertise errorCode=1（advertise data 超 31B，watch-engineer 修正中）。
### 3.1 建置安裝
1. Android Studio 開啟 watch-app/ → sync → Build APK。
2. `adb -s %WT% install -r <apk路徑>`（%WT% = %WT%，USB 連線）。
3. 手錶端無需 pm grant（Android 11 無 BLUETOOTH_SCAN 執行期權限；BLUETOOTH／BLUETOOTH_ADMIN 為 normal 權限）。
4. ⚠️ 手錶為 32-bit 系統（probe P1：armeabi-v7a）→ **不得**在 gradle 設 abiFilters 排除 armeabi-v7a，否則安裝報 INSTALL_FAILED_NO_MATCHING_ABIS（純 Kotlin 無原生庫時預設 APK 即含 v7a）。
### 3.2 深連結功能測試（architecture.md 定案之 debug 深連結）
> ⚠️ ColorOS Watch 擋 shell 廣播 → 本節一律用深連結（broadcast 版 DEBUG_END 已廢除；停止模擬改用 debug_end 深連結，extra 名稱以 watch-app README 為準）。
```
set WT=%WT%
adb -s %WT% shell am start -n com.linewatch.watch/.IncomingCallActivity --es name 測試 --ez missed false
adb -s %WT% shell am start -n com.linewatch.watch/.IncomingCallActivity --es name 測試 --ez missed true
:: 模擬停止（等同 BLE end，watch-engineer 修改中）：
adb -s %WT% shell am start -n com.linewatch.watch/.IncomingCallActivity --es debug_end true --ez missed false
```
- 來電（missed false）：黑底、LINE 綠圓點＋「LINE 來電」20sp、名字 40sp 置中、下方「● 震動提醒中」（LINE 綠）、震動 [600,400] 循環。
- 未接（missed true）：標題「LINE 未接來電」（警示色 #FF8A65）、副標「對方可能已掛斷」12sp 灰、8 秒後自動 finish、震動 [300,200,300] 一次。
- 震動成對啟停：來電中轉未接 → 不重啟震動；`adb -s %WT% shell am force-stop com.linewatch.watch` 後震動應全停。
- 驗收：兩狀態顯示正確、震動成對啟停、8s 自動關。
### 3.3 看門狗（手錶 120s）
- 用 `--ez missed false` 啟動後不做任何事 → 約 120s 自動停止並轉未接畫面 8s。
- 驗收：logcat 有 watchdog 事件；總時長 ≈ 120s＋8s。
### 3.4 藍牙斷線提示
- 非震動中斷線（關手機藍牙或 force-stop 手機 app）→ 手錶顯示「藍牙已斷線」。
- 震動中斷線 → 由 120s 看門狗收尾（不另行打斷）。
- 驗收：兩種情境行為正確。
### 3.5 BAL 實測（承接 probe-report.md P7）— 已實測：⚠️ 受限 → overlay 備援已落地
- 主路徑實測結果：背景啟動被攔（logcat：Abort background activity starts from 10106、isBgStartWhitelisted: false）→ **啟用 overlay 備援**。
- overlay 落地（watch-engineer 完成，integration-tester 靜態驗證 ✅）：主路徑 startActivity 保留；拋例外 → 立即 overlay；靜默 Abort → 1.5s 不可見判定後 overlay；OverlayHelper 三態（來電／未接／斷線，未接與斷線 8s 自動關、震動由 service 管）；SCREEN_ON 亮屏重試 Activity 並交還（Activity onStart 關 overlay）。
- 前置授權：`adb -s %WT% shell appops set com.linewatch.watch SYSTEM_ALERT_WINDOW allow`（或手錶設定允許「顯示在其他應用程式上層」）。
- 隔離測試（TEST_BG_START broadcast）跳過：ColorOS 擋 shell 廣播，主路徑證據已足夠；receiver 保留，重測預期仍 blocked_or_timeout（已知）。
- 剩餘驗收（T3/T4 一併）：overlay 在來電／未接／停止三情境正確顯示與關閉；亮屏後交還 Activity；使用者級「允許後台啟動」開關（若有）開啟後 Activity 路徑背景可用性。
- BAL 結果已回填 probe-report.md P7。
### 3.6 FGS 常駐
- `adb -s %WT% shell dumpsys activity services com.linewatch.watch` 確認 BlePeripheralService 為 foreground 且有常駐通知。
- 驗收：服務 isForeground=true。

## 4. T4 整合測試（integration-tester 主導；核心驗收）
### 4.0 前置
- 兩 APK 已裝、權限齊、兩台藍牙開（P8=1）、手機省電白名單完成（P12）。
- 手錶 overlay 授權（BAL 受限 → 顯示備援）：`adb -s %WT% shell appops set com.linewatch.watch SYSTEM_ALERT_WINDOW allow`，並以 `adb -s %WT% shell appops get com.linewatch.watch SYSTEM_ALERT_WINDOW` 確認回 allow。
- 第二 LINE 帳號：另一支手機／平板／PC 裝 LINE 登入第二帳號，與測試機互為好友（真實來電驗收用）。
- 開兩個 cmd 視窗收雙端 log（見 §0）。
- 已知現象：手機 OHealth 會把 LINE 通知鏡像到錶（系統通知卡片）→ 與本 app 顯示並存，記錄疊加現象即可，不影響判定。
### 4.1 連線建立
1. 開啟手機 app → 手錶 BlePeripheralService 開 GattServer＋advertise（LOW_LATENCY）。
2. 手機狀態列轉「已連線（手錶 OPP…15C）」；logcat：GATT connect＋MTU 247；手錶收到指令回 ack。
3. 心跳：每 10s ping→pong（雙端 log 對照 seq）。
- 驗收：連線保持 5 分鐘無斷、心跳規律、JSON 符合 protocol.md（UTF-8、≤200 bytes）。
### 4.2 測試按鈕全鏈路（免真實通話）
- 測試來電 → 手錶顯示來電畫面（螢幕亮：Activity；螢幕未亮：overlay 備援）＋持續震動＋名字；停止測試 → 立即停震、畫面關；測試未接 → 未接畫面 8s。
- 驗收：三按鈕行為全對，雙端 log JSON 正確。
### 4.3 真實 LINE 語音來電驗收（核心）
四場景矩陣（各至少 1 次；用第二帳號撥打測試機 LINE）：
| 場景 | 操作 | 期望 |
|---|---|---|
| A 來電中 | 撥打，測試機不接 | 手機響 → 手錶 ≤3s 內顯示來電者名字並震動（亮屏 Activity／未亮屏 overlay 皆算，overlay 含 1.5s 判定延遲）；持續震動 |
| B 接聽 | 測試機接聽 | 通知消失 → 手錶立即停震、畫面關、無未接畫面 |
| C 拒接 | 測試機拒接／掛斷 | 同 B（end missed=false） |
| D 未接 | 對方掛斷 → 手機出現未接通知 | 手錶顯示「LINE 未接來電：名字」8s 後自動關（repost-missed→end(missed=true)，或 v1.1 5s 窗補送 t="missed"，或 C-lite：5s 到期無訊號預設 missed）。**已知可接受行為（captain 裁示 C-lite）**：拒接後 5s 亦顯示未接畫面，不視為失敗；接聽（Ongoing 訊號）正確排除 |
- 名稱顯示：中文名／英文名／未知聯絡人（非好友或無名稱）各測一次；視訊來電變體測一次（標題「LINE 視訊來電」）。
- 驗收：四場景全過、雙端 log 無 FATAL；來電提醒成功率 ≥ 4/5。
### 4.4 邊界
- 名字長度：1 字、20 字、emoji、超 60 bytes（應截斷不崩潰）。
- 連續兩通來電間隔 <10s：狀態機正確回 IDLE 再進 RINGING。

## 5. T5 穩定化（integration-tester 主導）
### 5.0 T4 遺留（併入 T5 常規驗證）
- C-lite 真實三情境複測：接聽（不顯示未接）／拒接（顯示未接 8s，屬已知可接受行為）／對方掛斷（顯示未接 8s）。判讀 log：removed→end(false)→5s 窗→「verdict window expired -> default missed」或 ongoing 抑制。
- 手錶離充電座顯示路徑觀察（亮屏 Activity／關屏 turnScreenOn／overlay 備援不觸發）。
### 5.1 連續運行 2 小時
- 兩 app 常駐；真實 LINE 來電每 20 分鐘 1 次（共 6 次），中間以測試按鈕補測。
- 驗收：全程無 crash／ANR（logcat 掃 `FATAL`、`ANR in`）；每次來電提醒成功。
### 5.2 斷線重連
- 手錶關藍牙 40s（> 3 次 pong 超時）→ 重開 → 手機 backoff 重連成功；重連後若狀態已回 IDLE 且未送過 end → 補送 `end(missed=true)`。
- 循環 3 次；另測手機開飛航模式 60s 情境。
- 驗收：每次都能重連、補送邏輯正確。
### 5.3 看門狗複測
- 手機 90s：按「測試來電」後不按停止 → 90s 自動送 end(missed=true) → 手錶未接畫面。
- 手錶 120s：T3 §3.3 複測。
- 驗收：秒數誤差 ≤5s。
### 5.4 耗電
- 以 T1 P9 基線對照：2 小時前後 `dumpsys battery` 電量差；雙端 `dumpsys batterystats` 檢查 app 喚醒次數無異常增長。
- 驗收：記錄數值；手錶 2h 耗電 >30% 或手機異常掉電 → 回報 captain 分析。
### 5.5 記憶體
- `adb -s %PH% shell dumpsys meminfo com.linewatch.phone`、`adb -s %WT% shell dumpsys meminfo com.linewatch.watch` → 無明顯洩漏趨勢（2h 成長 <20MB 為合理）。
### 5.6 通知格式變化 fallback
- CallParser 單元測試擴充多組 LINE 通知樣本（不同版本語句／關鍵字變化），全綠。
- 驗收：解析 fallback「未知聯絡人」與 missed 判定正確。
### 5.7 重開機恢復
- v1 已含開機自啟（architecture.md 手錶端元件第 5 項 BootReceiver，captain 裁示保留）。
- 兩台重開機 → 手錶 BootReceiver 自動重啟 BlePeripheralService；手機手動開啟 app → 自動重連手錶；來電提醒恢復。
- 驗收：重開機後 30s 內手錶 FGS 恢復（`adb -s %WT% shell dumpsys activity services com.linewatch.watch` 確認 isForeground=true）；手機 app 開啟後 30s 內重連成功。
- 若 ColorOS Watch 攔截自啟動（服務未起來）→ 依 watch-app README 的自啟動白名單操作允許後複測，並把實際路徑記錄到測試記錄表。

## 附錄 A 測試記錄表
| 日期 | 階段／項目 | 執行者 | 結果（P/F） | 證據（log 檔名／貼回內容） | 備註 |
|---|---|---|---|---|---|
|  |  |  |  |  |  |

## 附錄 B 環境實測結論（2026-08-17 探測確認，見 probe-report.md）
- 手錶：armeabi-v7a（⚠️32-bit 系統！APK 不可排除 v7a）、466x466／density 320（70%≈163dp）、Android 11 SDK 30、BLE 可用（GATT Server 有 heytap.accessory 運行先例）、系統語言 zh-CN（UI 依 ui-spec 繁中）、名稱「OPP…15C」。
- 手機：arm64-v8a、Android 16（sdk 36）、LINE 26.11.0 已裝、bluetooth_le 齊全（另有 channel_sounding）。
- 手錶藍牙 profile 無 SPP（D1 複驗通過）；兩台系統層已配對（OPPO Watch X3（<裝置尾碼>））；手機 OHealth 已裝且會鏡像轉發 LINE 通知（T4 注意疊加現象）。
