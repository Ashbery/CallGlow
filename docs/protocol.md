# BLE 通訊協議規格 v1.1（唯一協議權威文件；v1.1 新增 t="missed" 補送指令，見文末章節）

## 角色與拓撲
- 手錶端 = GATT Peripheral（常駐前台服務，開 GattServer + 廣播）
- 手機端 = GATT Central（前台服務維護連線，主動重連）

## UUID（Nordic UART Service）
| 名稱 | UUID |
|---|---|
| SERVICE | 6E400001-B5A3-F393-E0A9-E50E24DCCA9E |
| CHAR_CMD（手機→手錶） | 6E400002-B5A3-F393-E0A9-E50E24DCCA9E（WRITE / WRITE_NO_RESPONSE） |
| CHAR_STATE（手錶→手機） | 6E400003-B5A3-F393-E0A9-E50E24DCCA9E（NOTIFY） |

## 訊息格式
- 單一 JSON 物件、UTF-8、一次寫入完成；上限 200 bytes（name 截斷 60 bytes）。
- 手機連上後先 requestMtu(247)；訊息通常 < 100 bytes。
- 手機→手錶：
  {"t":"start","name":"王小明","kind":"voice"}     kind ∈ voice | video
  {"t":"end","missed":false}
  {"t":"end","missed":true}
  {"t":"missed","name":"王小明","kind":"voice"}   v1.1：接聽/拒接停止後，未接判定窗內補送（見下）
  {"t":"ping","seq":1}
- 手錶→手機：
  {"t":"pong","seq":1}
  {"t":"ack","type":"start"}  /  {"t":"ack","type":"end"}  /  {"t":"ack","type":"missed"}（建議實作，供手機 log）

## 心跳與重連
- 連線空閒時手機每 10s 送 ping；手錶回 pong。
- 手機連續 3 次無 pong（約 30s）→ 斷線重連（backoff 5s/15s/30s 上限）。
- 手錶端 onConnectionStateChange 偵測斷線：震動中 → 交由 120s 看門狗收尾；
  非震動中 → 顯示「藍牙已斷線」提示。
- 手機重連成功後，若本機狀態已回 IDLE 但未送過 end → 補送 {"t":"end","missed":true}。

## 看門狗（雙保險）
- 手機：RINGING 開始後 90s 無結束事件 → 自動送 {"t":"end","missed":true}。
- 手錶：收到 start 後 120s 未收到 end → 自動停止（視為 missed 顯示 8s）。
- 秒數為常數，改動必須同步改本文件與 decisions.md，兩端一致。

## 名稱解析（手機端 CallParser）
- 來源：android.title → android.subText → android.text 首行。
- 過濾字：語音通話／視訊通話／來電／Voice／Video／Call 等。
- 抓不到 → 「未知聯絡人」。

## missed 補送指令（v1.1，T4 實測新增）
- 背景：實測發現 LINE 掛斷後，未接通知常在「removed → end(false)」之後才到達（或只放 subText），
  且狀態機已回 IDLE。為不影響接聽/拒接的「立即停震」驗收，手機端在送完 end(false) 後武裝
  一次性的 5 秒未接判定窗：窗內收到 LINE 未接通知（任何 key）→ 補送 t="missed"。
- 手錶端處理：RINGING 中收到 → 視同 end(missed=true)；IDLE 收到 → 顯示「LINE 未接來電」畫面 8 秒
  （不震動）；回 ack type="missed"。
- 窗一次有效、只在真實響鈴→removed 後武裝，防止舊通知洪流。

## v2 頭像傳輸（T8，2026-08-17 captain 批准，源自 protocol-v2-avatar-draft.md）
背景：LINE 來電通知 largeIcon 攜帶對方頭像（156×156 實測）；來電畫面先顯示 T7 首字頭像，
頭像到達後無縫替換；傳輸 ≤3s、不阻塞 start/end。

手機→手錶（CHAR_CMD）：
- {"t":"av_start","ts":<ms>,"total":N,"bytes":M}    WRITE（有回應）
- {"t":"av_chunk","ts":<ms>,"i":<idx>,"d":"<base64>"}   WRITE_NO_RESPONSE（每塊 336B→b64 448 字元，整條訊息 ≈498 ≤500B）
- {"t":"av_end","ts":<ms>,"sha":"<64 hex>"}   WRITE（有回應）

手錶→手機（CHAR_STATE）：
- {"t":"ack","type":"av_end"}   驗證通過（來電中 → 顯示）
- {"t":"av_fail","ts":<ms>,"reason":"sha|missing|timeout"}   失敗；手機對任何 reason 皆重試一次（同資料），再失敗放棄

參數：96×96 JPEG q80（>12KB 降 60→40）；塊 336B；塊間 4ms；整場逾時 5s（含重試）；ack 等待 2s；
同時間僅一個 session；新 start/end/missed/ongoing/BLE 斷線 → 兩端各自中止。
中止語義：手機中止 → 不再送塊；手錶遇新 start/end/missed/BLE 斷線 → 靜默丟棄緩衝（不回 av_fail，
因手機同條件也會中止）；av_fail 僅用於 sha／missing／timeout。
顯示規則：僅 CALLING 中收到且驗證通過才替換 T7 首字頭像；未接／斷線畫面維持首字；
晚到（已 end）→ 仍回 ack av_end（傳輸本身成功）但不顯示；下一通來電 start 時清空頭像庫。

修訂：v1「訊息 ≤200B」僅適用 v1 訊息；av_chunk 放寬 500B（≤MTU-3）。

## 版本相容
未知欄位一律忽略；未知 t 值忽略（向前相容）。
