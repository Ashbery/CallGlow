# T4 整合測試執行清單（2026-08-17，captain 直接執行 adb）

> 維護者：integration-tester。驗收準則見 docs/test-plan.md §4。此清單的指令全部可貼進 cmd；
> 序號：手機 %PH%=%PH%、手錶 %WT%=%WT%（USB）。
> 使用者只需配合「用第二 LINE 帳號打一通電話」；其餘 captain 以 adb 完成。判讀由 integration-tester 回填 T4 報告。

## 0. 前置（Phase 0，約 2 分鐘）

```
set PH=%PH%
set WT=%WT%
adb -s %PH% shell settings get global bluetooth_on
adb -s %WT% shell settings get global bluetooth_on
adb -s %PH% shell setprop log.tag.LineWatchPhone V
adb -s %WT% shell appops get com.linewatch.watch SYSTEM_ALERT_WINDOW
adb -s %WT% shell dumpsys activity services com.linewatch.watch | findstr /i "isForeground"
adb -s %PH% shell dumpsys deviceidle whitelist | findstr /i linewatch
adb -s %PH% shell settings get secure enabled_notification_listeners
```

判讀：bluetooth_on 兩台皆 1；SYSTEM_ALERT_WINDOW = allow；isForeground=true；deviceidle 有 com.linewatch.phone；enabled_notification_listeners 含 com.linewatch.phone。
任一不符 → 先補對應步驟（test-plan §2.2/§3.5/P12）再繼續。

## 1. logcat 收集（全程開兩個 cmd 視窗）

```
adb -s %PH% logcat -c
adb -s %WT% logcat -c
adb -s %PH% logcat -s LineWatchPhone:* AndroidRuntime:E -v time > t4_phone.log
adb -s %WT% logcat -s LineWatchWatch:* AndroidRuntime:E ActivityTaskManager:I -v time > t4_watch.log
```

每階段結束後，把兩檔尾部（該階段的時間段）貼回；檔案留本機。

## 2. Phase 1：BLE 連線建立（免通話）

操作：手機開 app → 開總開關（若 app 未在前台：`adb -s %PH% shell am start -n com.linewatch.phone/.MainActivity`）。
觀察 60~90s：
- 手機狀態列「藍牙：已連線（手錶 OPP…15C）」。
- phone.log：GATT connect、MTU=247、每 10s ping。
- watch.log：`{"t":"connect","addr":...}`、pong、ack。

判讀矩陣：
| 觀察 | 判讀 |
|---|---|
| 狀態列已連線＋雙端 log 心跳規律 | ✅ 通過 → Phase 2 |
| 掃描中/已斷線且 log 無 connect | ❌ 查 NUS UUID 過濾與 advertise（watch.log errorCode） |
| connect 後 30s 內斷 | ❌ 查 pong 超時與 backoff 順序（5s/15s/30s） |

## 3. Phase 2：測試按鈕全鏈路（免通話）

操作（手機 app 按鈕）：測試來電 → 觀察 → 停止測試 → 觀察 → 測試未接 → 觀察。
螢幕狀態變體（各跑一輪）：
- 手錶亮屏：`adb -s %WT% shell input keyevent 224`（喚醒）→ 預期 Activity 全螢幕顯示。
- 手錶關屏：`adb -s %WT% shell input keyevent 26`（休眠）→ T3 實測：turnScreenOn 讓 **Activity 主路徑成功亮屏**（overlay 1.5s 備援就緒但未觸發）。預期 Activity 亮屏顯示；若 watch.log 出現 ActivityTaskManager Abort → overlay 1.5s 後接手（也算通過）。

判讀矩陣：
| 按鈕 | 期望 | 通過判讀 |
|---|---|---|
| 測試來電 | 顯示「LINE 來電＋名字＋● 震動提醒中」＋[600,400] 持續震動 | 畫面正確＋持續震動；watch.log 有 ack start |
| 停止測試 | 立即停震、顯示關閉 | 震動停、overlay/Activity 關；log end |
| 測試未接 | 「LINE 未接來電」警示色＋副標、8s 自動關、[300,200,300] 一次 | 8s±1s 自動關；log end missed=true |

## 4. Phase 3：真實 LINE 語音來電（四場景矩陣）

前置：請使用者以第二 LINE 帳號（另一手機/平板/PC）撥打測試機 LINE；測試機不靜音、LINE 通知允許。
⚠️ 手錶必須**離開充電座**（ColorOS 充電畫面會蓋住顯示，第一輪實測即因此未能驗證畫面路徑）。
每場景記錄：撥打時刻、手錶反應延遲、畫面文字、震動是否持續、結束後表現；對照雙端 log JSON。

| 場景 | 操作 | 期望（判讀通過條件） |
|---|---|---|
| A 來電中 | 撥打、測試機不接 | ≤3s 內手錶顯示來電者名字並持續震動（亮屏 Activity／未亮屏 overlay 皆算）；phone.log 有 start（kind=voice）、watch.log 有 ack |
| B 接聽 | 測試機接聽 | 通知消失 → 立即停震、顯示關閉、無未接畫面；log end missed=false |
| C 拒接 | 測試機拒接/掛斷 | 同 B（end missed=false） |
| D 未接（C-lite 裁示） | 對方掛斷 → 手機出現未接通知 | 裁示採 C-lite：removed→end(false) 後 5s 窗內——ongoing 訊號→接聽抑制；未接通知→t="missed"；**到期無訊號→預設 missed**。複測三情境：接聽（不顯示未接）／拒接（顯示未接 8s，屬已知可接受行為）／對方掛斷（顯示未接 8s ✅）。重測時收集 LINE 通知 DEBUG log 佐證 |

變體（各 1 次）：
- 視訊來電：標題「LINE 視訊來電」（kind=video）。
- 名稱：中文名／英文名／未知聯絡人（非好友撥打或無顯示名）。

## 4.1 logcat 判讀關鍵字總表

手機端（tag LineWatchPhone）：
| 關鍵字 | 意義 |
|---|---|
| `{"t":"start","name":...,"kind":"voice"/"video"}` | 判定來電並送出 start |
| `{"t":"end","missed":false}` | 接聽／拒接／通知移除 → 送 end |
| `{"t":"end","missed":true}` | 未接（repost-missed）或 90s 手機看門狗 → 送 end missed |
| `{"t":"missed","name":...,"kind":...}` | v1.1：end(false) 後 5s 未接判定窗內補送（D 場景） |
| `{"t":"ping","seq":N}` | 心跳（每 10s） |
| GATT connect／MTU／reconnect | 連線建立與重連（backoff 5s/15s/30s） |

手錶端（tag LineWatchWatch）：
| 關鍵字 | 意義 |
|---|---|
| `{"t":"ack","type":"start"}` | 收到 start → 顯示＋震動 |
| `{"t":"ack","type":"end"}` | 收到 end → 停止 |
| `{"t":"missed","name":...}` → `{"t":"ack","type":"missed"}` | v1.1 補送：RINGING→視同 end(missed=true)；IDLE→未接畫面 8s 不震動 |
| `{"t":"pong","seq":N}` | 心跳回應 |
| `{"t":"watchdog","timeoutMs":120000}` | 120s 手錶看門狗觸發 |
| ActivityTaskManager: Abort background activity starts | 背景啟動被攔 → overlay 接手（預期內） |
| AndroidRuntime: FATAL | ❌ crash，立即回報 |

四場景關鍵字對照：
| 場景 | phone.log 必見 | watch.log 必見 | 手錶行為 |
|---|---|---|---|
| A 來電中 | start（kind=voice） | ack type=start | 顯示名字＋持續震動 |
| B 接聽 | end missed=false | ack type=end | 立即停震、顯示關閉 |
| C 拒接 | end missed=false | ack type=end | 立即停震、顯示關閉 |
| D 未接 | end missed=true（repost-missed 或 90s watchdog），或 t="missed"（5s 窗補送） | ack type=end／ack type=missed | 未接畫面 8s 自動關 |

## 4.2 手錶畫面／震動判讀準則

- 畫面（來電）：全螢幕黑底、LINE 綠圓點＋「LINE 來電」20sp（視訊→「LINE 視訊來電」）、中央名字 40sp 粗體白字（無名→「未知聯絡人」）、下方「● 震動提醒中」LINE 綠。
- 畫面（未接）：標題「LINE 未接來電」警示色 #FF8A65＋副標「對方可能已掛斷」12sp 灰，8s±1s 自動關。
- 震動：來電 [600,400] 無限循環（震 0.6s 停 0.4s 重複）；end 後 ≤1s 停止；未接 [300,200,300] 單次。
- 顯示路徑：亮屏／關屏皆預期 Activity（turnScreenOn）；僅當 watch.log 出現 Abort 時 overlay 1.5s 內接手（兩者皆算通過）。
- 記錄格式：延遲秒數＋畫面文字＋震動節奏描述＋（截圖可）。

## 5. Phase 4：回傳給 integration-tester（用 §7 模板）

每階段貼回場景記錄卡＋雙端 log 對應段落。我逐項判讀並回填 T4 報告（記錄表見 test-plan 附錄 A）。

## 6. 已知現象（不影響判定的記錄項）
- OHealth 鏡像通知：來電時手錶可能另彈系統通知卡片（Heytap 轉發）→ 記錄疊加現象即可。
- ColorOS 通知中心拒顯 FGS 常駐卡片（已知，不影響功能）。

## 7. 報告模板（captain 每階段回傳用）
```
【場景記錄卡】
- 場景：A／B／C／D／視訊變體／名稱變體　時間：
- 撥打者 LINE 顯示名：
- 手錶反應延遲（s）與顯示路徑（Activity／overlay）：
- 手錶畫面文字（截圖可）：
- 震動（持續／停、節奏）：
- phone.log 關鍵行（3~5 行）：
- watch.log 關鍵行（3~5 行）：
- 其他觀察（OHealth 鏡像疊加等）：
- 初步判定：P／F
```
總結表（integration-tester 回填）：
| 場景 | 次數 | 結果 | 備註 |
|---|---|---|---|
| A 來電中 |  |  |  |
| B 接聽 |  |  |  |
| C 拒接 |  |  |  |
| D 未接 |  |  |  |
| 視訊變體 |  |  |  |
| 名稱變體 |  |  |  |
驗收門檻（test-plan §4.3）：四場景全過、雙端 log 無 FATAL、來電提醒成功率 ≥4/5。
- 手錶 BAL 受限：未亮屏時 Activity 可能被 Abort → overlay 1.5s 接手（T3 實測 turnScreenOn 主路徑關屏亦成功，overlay 僅在 log 出現 Abort 時觸發；watch.log 的 ActivityTaskManager: Abort background activity starts 屬預期、非故障）。
- ColorOS 通知中心拒顯 FGS 常駐卡片（已知，不影響功能）。
