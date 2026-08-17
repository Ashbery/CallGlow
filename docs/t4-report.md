# T4 整合測試報告（✅ 完成 2026-08-17）

> 維護者：integration-tester。驗收準則 docs/test-plan.md §4；執行清單 docs/t4-execution.md。

## 最終結果摘要

| 場景 | 結果 | 證據 |
|---|---|---|
| A 來電中 | ✅ 4/4 | ≤2s BLE start、手錶 CALLING、震動 [600,400]、ack |
| B 接聽 | ✅ 重測通過 | removed→end(false) 接聽瞬間停震；Ongoing 貼出後正確 ignored |
| C 拒接 | ✅ 重測通過 | removed reason=8→end(false) 停震 |
| D 未接 | ✅ 實作完成（C-lite）＋單測覆蓋；真實來電三情境複測**併入 T5** | LINE 實測不貼未接通知（兩次證據）→ captain 裁示 C-lite：removed→end(false)＋5s 窗（窗內 ongoing→answered 抑制；未接通知→missed；到期無訊號→預設 missed）。實作經 integration-tester 靜態驗證：MissedVerdict 三態＋onOngoing markAnswered（含 IDLE）＋onVerdictExpired 回 MISSED＋listener 5s 到期回呼與證據 log ✅。56 JVM 用例全綠 |
| 視訊變體 | ✅ | kind=video 正確區分 |
| 名稱變體 | ✅ | 林芃宇 解析正確（中文名） |
| 顯示路徑 | ✅ T3 已驗證 | 亮屏 Activity／關屏 turnScreenOn 主路徑；overlay 備援就緒（T4 首輪手錶在充電座未複驗畫面，T5 常規驗證一併觀察） |

驗收結論：四場景核心行為全部達標（D 以 C-lite 裁示為準）；雙端 log 無 FATAL；來電提醒成功率 4/4。
遺留（掛 T5）：C-lite 真實三情境複測（接聽不顯示未接／拒接顯示未接屬可接受／對方掛斷顯示未接）；充電座外顯示路徑觀察。

## 重測第一輪（2026-08-17 15:25~15:28，修復版 APK，完整四通時間線）

| 通次 | 場景 | 序列（logcat） | 結果 |
|---|---|---|---|
| 1 | C 拒接 | start 於清 log 前 → 15:25:13 removed reason=8 → end(false) → 停震 | ✅ 通過 |
| 2 | B 接聽 | 15:25:46 start → 15:25:51 removed reason=8 → end(false) 立即停震 → 15:25:51.8 Ongoing 貼出 →「ongoing while idle, ignored」（正確忽略）→ 15:25:57 Ongoing 移除（掛斷） | ✅ **接聽即停修復生效** |
| 3 | D 未接（LINE 後台） | 15:26:14 start → 15:26:29 removed reason=8 → end(false)、armVerdict=true → **5s 窗內無任何未接通知** → 無未接畫面 | ❌ LINE 不貼未接通知 |
| 4 | D 未接（重試，LINE 後台） | 15:27:36 start → 15:27:42 removed reason=8 → end(false)、armVerdict=true → **5s 窗內無任何未接通知** → 無未接畫面 | ❌ 再次證實 LINE 不貼未接通知 |

（前述「第 3 通零事件」為監控窗時序失誤之勘誤；實際 4/4 通皆有完整事件。）
兩次未接嘗試（通 3、4）都證明：LINE 在「螢幕亮/已解鎖」環境連後台都不貼未接通知 → C-lite 裁示（見下）。

### D 場景產品決策（✅ captain 裁示 2026-08-17：採 (b) C-lite）
背景：CallLog 探測排除 (c)（LINE 不寫系統通話紀錄）；LINE 在「螢幕亮/已解鎖」環境連後台都不貼未接通知。
裁示：**採 (b) C-lite**。phone-engineer 實作中：removed → end(false)＋5s 窗；窗內 ongoing → answered 抑制；未接通知 → missed；**到期無訊號 → 預設 missed**。
驗收準則（已同步 test-plan §4.3）：拒接後 5s 顯示未接畫面屬「已知可接受行為」；接聽（Ongoing 訊號）正確排除、不顯示未接。
複測矩陣：接聽（不顯示未接）／拒接（顯示未接，可接受）／對方掛斷（顯示未接 ✅）。
證據基礎：重測通 3、4 兩次未接（LINE 後台）皆無未接通知，證據充分（早前「第三通疑點」為監控時序誤會，已勘誤撤銷）。

### 頭像突破（v2 roadmap，不影響 v1）
Incoming 與 Ongoing 通知皆帶 largeIcon=156x156（對方 LINE 頭像）→ v2 真頭像方案成立：通知取圖 → 壓縮 → BLE 分塊傳輸。已記 roadmap。

## 第一輪（2026-08-17 15:03~15:05，真實 LINE ×4，logcat 證據由 captain 收集）

| 通次 | 時間 | 類型 | 名字 | 序列 | 初步結果 |
|---|---|---|---|---|---|
| 1 | 15:03:51 | voice | 林芃宇 | start ✅ → 15:04:12 removed → end(false)（通話 21s，對方掛斷） | ❌ 未見 missed=true → 手錶無未接畫面（D 場景失敗） |
| 2 | 15:04:22 | voice | — | start ✅ → 15:04:39 end(false)（17s） | ✅ B/C 行為正常 |
| 3 | 15:04:39 | voice | — | start ✅ → 15:04:49 end(false)（10s） | ✅ |
| 4 | 15:05:30 | **video** | — | start ✅（kind=video 正確）→ 15:05:45 end(false) | ✅ 視訊辨識正確 |

結果摘要（場景對應經使用者確認）：
- A 來電中 4/4 ✅：≤2s BLE start 送達、手錶 CALLING、震動 [600,400]、ack 正常。
- C 拒接（第 3 通）✅：end(missed=false)、震動即停（mCurrentVibration=null 實測）。
- B 接聽（第 4 通，視訊）❌：使用者回報接聽後仍持續震動到掛斷；logcat 顯示接聽當下無 end 事件——LINE 把來電通知轉為「通話中」頻道 VoIP.02.Ongoing，直到掛斷才 removed。parser 未處理接聽轉換。已派 phone-engineer 最高優先修復。
- 視訊變體 ✅：kind=video 正確區分。
- D 未接（第 1 通）❌：removed → end(false) 之後沒有 missed=true → 手錶未顯示「LINE 未接來電」（根因分析見下）。
- 顯示路徑 ⚠️ 未驗證：手錶在充電座（充電畫面 100% 蓋住）；震動不受影響。需離座重測。

## B 場景（接聽）根因分析 — 2026-08-17 追加

實測序列：post(ringing, VoIP.01 頻道) → start ✅ → 使用者接聽 → LINE 以「通話中」通知取代（頻道 VoIP.02.Ongoing，可能同 key 或新 key）→ 掛斷才 removed → end(false)。

程式碼路徑：
1. 接聽後的通話中通知：isCall() 判定不定（category 可能仍是 CATEGORY_CALL → 命中；或 title/text 為「通話中 xx:xx」→ 「通話中」不在 CALL_KEYWORDS（只有 語音通話／視訊通話）→ 可能不命中）。
2. 命中時：同 key → 回 none()（重複）；**新 key → 被當「插播新來電」→ 重送 start（震動重啟）**。
3. 未命中時：直接忽略 → 無 end。
→ 三條路徑都不會產生 end(missed=false) → 震動持續到掛斷 removed 才停。

修正方向（phone-engineer 實作，處理順序優先於 isCall/missed 判定）：
- RINGING 狀態下，posted 通知的 channelId 含「Ongoing」（如 VoIP.02.Ongoing）或 text 含「通話中」→ 判定為接聽轉換 → 立即 end(missed=false)。
- 在 isCall() 之前先做此判定，避免新 key 被當插播重送 start。
- 掛斷後 removed → end(false) 重複送屬無害（watch 端 idempotent），但建議去重。

## D 場景根因分析（證據更新 2026-08-17 晚）

**證據定案**：t4_phone.log 無任何「ignored (missed while idle)」行 → **非狀態機忽略問題**，而是 **LINE 在對方掛斷後根本沒貼系統未接通知**（推測：當時 LINE 在前台，未接以 App 內橫幅顯示，不走系統通知）。使用者已確認第一通為對方掛斷、非接聽。

修正對策（已派 phone-engineer 三項）：
1. 接聽→通話中偵測：RINGING＋channelId 含 Ongoing／text 含「通話中」→ 立即 end(missed=false)（修 B）。
2. IDLE＋missed 5s 短窗補償（protocol v1.1／D11，captain 批准）：涵蓋 LINE 在**背景**貼未接通知的場景（removed 後 5s 內 missed → 補送 t="missed"，手錶 IDLE 顯示未接畫面 8s 不震動）。
3. LINE 全部通知 DEBUG log＋removal reason log：重測時收集完整證據（哪個頻道、哪種 removal）。

已知邊界：LINE 在前台時可能永不貼未接通知 → 短窗補償也無從觸發 → 此情形下「無未接畫面」為 LINE 行為限制，需記錄為已知限制（除非後續決定 CallLog 監聽，屬 v1 範圍外，由 captain 裁示）。

## 待辦清單（重測矩陣 v2）
- [x] phone-engineer 三項修復＋單元測試（43 用例：CallStateMachine 18＋MissedVerdict 5＋CallParser 18＋RemovalReason 2）——**integration-tester 靜態驗證通過 2026-08-17**：isOngoing 優先於 isCall/missed、頻道主判定＋文字備援＋響鈴文案排除、removed reason 分類（使用者動作不武裝窗）、MissedVerdict 5s 一次有效、Command.missed 格式符合 v1.1。
- [ ] captain 重 build 安裝後重測：
  - B 接聽 ×1（視訊或語音皆可）→ 接聽瞬間停震。
  - C 拒接 ×1 → 停震。
  - D 未接 ×2 變體：**LINE 在前台**（預期：可能無未接畫面，記錄為已知限制）vs **LINE 在背景**（預期：短窗補償 → 未接畫面 8s）。
  - Phase 2 按鈕＋**手錶離座**：亮屏 Activity／關屏 turnScreenOn 顯示路徑驗證。
  - 雙端 log 收齊（含新增的 LINE 通知 DEBUG log）。
- [ ] 全部通過後補正式場景記錄卡並收尾 T4。
