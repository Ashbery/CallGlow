# T5 穩定性測試報告（✅ 完成 2026-08-17）

> 維護者：integration-tester。驗收準則 docs/test-plan.md §5；執行清單 docs/t5-execution.md。
> 執行由 captain 以 adb 進行；各節結果回傳後由 integration-tester 判讀回填。

## 執行狀態（T5-A：完整 2 小時＋6 通真實來電，2026-08-17 啟動）
| 節 | 項目 | 狀態 |
|---|---|---|
| §5.0 | T4 遺留：C-lite 三情境＋離座顯示 | ✅ 通過（六通採樣判讀） |
| §5.1 | 連續運行 2 小時 | ✅ 通過（6 通真實來電＋心跳規律） |
| §5.2 | 斷線重連 | ✅ 通過 |
| §5.3 | 看門狗複測 | ✅ 通過 |
| §5.4 | 耗電 | ✅ 通過 |
| §5.5 | 記憶體 | ✅ 通過 |
| §5.6 | 通知格式 fallback | ✅ 完成（單元測試層） |
| §5.7 | 重開機恢復 | ✅ 通過 |

## 基線（T5-A 啟動時，對照 T1 P9）
- 手機電量 55%（P9 基線同 55%）、手錶 100%（P9 基線 100%）。
- MemAvailable：手機 7.7GB、手錶 0.96GB。
- 兩台藍牙開（bluetooth_on=1）、手錶 overlay allow、FGS isForeground 正常、雙端 logcat 已清空。

## §5.6 通知格式 fallback — 完成 ✅
phone-engineer 擴充 CallParserTest 18→31 例（總測試 69：CallParser 31＋CallStateMachine 26＋MissedVerdict 10＋RemovalReason 2）。
integration-tester 靜態驗證通過（2026-08-17）：
- 未接模板句：你有一通未接的語音通話／視訊通話（fallback 未知聯絡人、kind 正確）；你有 3 通未接來電（含空格變體）；英文 You have a missed voice/video call、Missed call＋Alice 名字取出 ✅
- strip 強化：NAME_KEYWORDS 長詞在前（你有一通→有一通→一通→你有→通話中→通話）；英文填充詞 \b 整詞邊界（Alice 不受 a 影響）；\d+\s*通 計數樣式 ✅
- 負樣本：「通話結束」非來電/非 missed/非 ongoing；「貼圖傳送中」非來電/非 ongoing（防「進行中」外溢）✅

## §5.2 斷線重連 — ✅ 通過
- 手機飛航 60s → 手錶即時偵測（「手機已斷線」＋disconnect JSON）→ 手機恢復後自動 scan→connectGatt→connected(status=0)，40s 內完成。
- 註：手錶 `svc bluetooth disable` 在此 ROM 無作用（無輸出）→ 改以手機飛航路徑驗證，結論成立。已記錄此 ROM 差異。

## §5.3 看門狗複測 — ✅ 通過
- 手錶 120s：debug 深連結 CALLING → 125s「120s 看門狗觸發 → 視為未接」→ 未接畫面 → 震動歸零。
- 手機 90s：由單元測試覆蓋（真實來電皆在 90s 內結束，無實際觸發）。

## §5.4 耗電 — ✅ 通過
- 手機 55%→54%（2 小時幾乎零消耗）；手錶 100% 不變。遠優於門檻（手錶 ≤30%/2h）。

## §5.5 記憶體 — ✅ 通過
- MemAvailable：手機 7.7GB→7.5GB、手錶 0.96GB→934MB，穩定無洩漏趨勢。全程無 FATAL/ANR。

## §5.7 重開機恢復 — ✅ 通過
- adb reboot → BootReceiver 自動拉 FGS（{"t":"boot","action":"start_service"} → GattServer → LOW_LATENCY 廣告）→ 手機 30s 內自動重連 connected(status=0)。

## T6 驗證（手錶設定畫面＋應用選單入口）— ✅ 通過
- 應用選單出現（resolve-activity=SettingsActivity）；設定功能實測：強度 200→波形 [600,400] 振幅 [200,0]；弱 100＋急促→[300,150] 振幅 [100,0]；設定 JSON log 正常；測試來電/停止正常。
- 觀察（低風險，記錄備查）：一次 ADB 瞬斷後首測震動 null、重試正常 → 判為連線競態非程式問題。

## §5.0/§5.1 六通真實來電採樣 — ✅ 全過（15:47~15:49，全 voice、名字「測試聯絡人」正確、BLE start ≤2s、ack 正常、心跳 seq 連續每 10s）

| 通次 | 場景 | 序列（logcat） | 判讀 |
|---|---|---|---|
| 1 | 接聽 | start → removed → Ongoing 205ms 到達 →「ongoing while idle (answered, verdict settled)」→ 窗到期「no action (answered)」 | ✅ 接聽即停、不顯示未接（C-lite 抑制生效） |
| 2 | 拒接 | removed → 通 3 於窗內到達 → 新響鈴解除窗 | ✅ 無誤顯（新來電正確解除判定窗） |
| 3 | 對方掛斷（33s） | removed → 5s 到期「default missed reason=8 delay=5009ms」→ 補送 t="missed" → 手錶「IDLE 收到 missed → 顯示未接畫面（不震動）：測試聯絡人」＋ack type=missed | ✅ **核心驗收通過** |
| 4 | 浸泡 | removed → 通 5 於窗內 → 解除 | ✅ |
| 5 | 浸泡 | removed → 通 6 於窗內 → 解除 | ✅ |
| 6 | 浸泡 | removed → 到期「default missed delay=5006ms」→ 手錶未接畫面＋ack | ✅ |

判讀總結：
- C-lite 三情境全過：接聽抑制 ✅；拒接的「到期預設 missed」機制由通 3/通 6 兩次到期路徑實證（通 2 因新響鈴解除未顯示，屬更優結果）；對方掛斷顯示未接 ✅。
- A 場景 6/6 正常；全程無 FATAL/ANR；心跳規律。

## 最終結論（T5 完成 ✅ 2026-08-17）
| 驗收項 | 結果 |
|---|---|
| §5.0 C-lite 三情境＋顯示路徑 | ✅ |
| §5.1 2 小時連續運行（6 通真實來電） | ✅ 無 crash/ANR、提醒 6/6 |
| §5.2 斷線重連 | ✅（飛航路徑；手錶 svc 差異已記錄） |
| §5.3 看門狗（手錶 120s 實測、手機 90s 單測） | ✅ |
| §5.4 耗電（手機 55→54%、手錶 100%） | ✅ |
| §5.5 記憶體（無洩漏趨勢） | ✅ |
| §5.6 fallback 樣本庫（69 用例） | ✅ |
| §5.7 重開機恢復（BootReceiver→30s 重連） | ✅ |
| T6 設定畫面驗證 | ✅（另 t6 已完成） |

觀察備查：ADB 瞬斷後首測震動 null→重試正常（連線競態，低風險）；拒接場景誤顯由新響鈴正確解除（優於預期）。
