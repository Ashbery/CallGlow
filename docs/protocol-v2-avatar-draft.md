# 協議 v2 草案 — 真實頭像傳輸（T8／roadmap V2-4-v2）

> 狀態：✅ 已批准（2026-08-17），已併入 docs/protocol.md v2 章節。
> 註：分塊常數定案為 336B（草案原 339B 會讓整條訊息 502 字元超標，實測修正為 336B→b64 448→訊息 ≈498）。
> 提出：phone-engineer。對齊：watch-engineer。

## 背景與事實依據
- T4/T5 實測：LINE 來電通知的 largeIcon 攜帶對方頭像（156×156，Incoming 與 Ongoing 皆有；
  EXTRA_PICTURE 亦可能出現）。→ 手機端無需 READ_CONTACTS／名稱比對（roadmap V2-4-v2 原評估作廢）。
- 現有鏈路：CHAR_CMD（WRITE / WRITE_NO_RESPONSE）手機→手錶；MTU 實測 517 → 單次寫入上限 514 bytes。
- 目標：來電畫面先顯示 T7 首字頭像（零延遲），頭像到達後無縫替換；傳輸延遲 ≤3s；不阻塞 start/end。

## 新增訊息（手機→手錶，皆走 CHAR_CMD）

| 訊息 | 欄位 | 說明 |
|---|---|---|
| `{"t":"av_start","ts":<ms>,"total":N,"bytes":M}` | ts=本次傳輸的 monotonic 時間戳（elapsedRealtime）；total=塊數；bytes=原始 JPEG 位元組數 | 開始；WRITE（有回應） |
| `{"t":"av_chunk","ts":<ms>,"i":<idx>,"d":"<base64>"}` | i=0 起；d=單塊 base64（標準 RFC4648，可能含 = 補齊） | 資料塊；WRITE_NO_RESPONSE（省來回） |
| `{"t":"av_end","ts":<ms>,"sha":"<64 hex>"}` | sha=SHA-256（原始 JPEG 位元組） | 結束＋完整性；WRITE（有回應） |

## 新增訊息（手錶→手機，CHAR_STATE notify）

| 訊息 | 說明 |
|---|---|
| `{"t":"ack","type":"av_end"}` | 收到 av_end 且 SHA-256 驗證通過 → 解碼；來電中才顯示 |
| `{"t":"av_fail","ts":<ms>,"reason":"sha|missing|timeout"}` | 失敗；手機對任何 reason 皆重試一次（同資料），再失敗放棄 |

## 參數（常數，改動需同步兩端與本文件）
- 壓縮：96×96 JPEG，品質 80（>12KB 時降 60→40 重編）；上限 12,000 bytes。
- 分塊：每塊 336 bytes（b64 後 448 字元，整條訊息 ≈498 ≤ 500 bytes ≤ MTU-3）。
- 傳輸節奏：chunk 間 4ms（配合頭像快取降低首傳延遲；不穩可回 8ms）；整場逾時 5s（含重試一次）；ack 等待 2s。
- 並行限制：同時間僅一個頭像 session；新 start／end／missed／ongoing／BLE 斷線 → 中止未完成 session。

## 手錶端行為（供 watch-engineer 實作）
1. 以 ts 為 session 鍵緩衝 av_chunk（跨 ping 等交錯訊息安全；未知 t 照舊忽略）。
2. 收到 av_end → 按序拼接（i 必須 0..total-1 完整無缺）→ SHA-256 比對 → 成功回 ack av_end、
   解碼 JPEG 顯示（失敗回 av_fail reason=sha）；缺塊/逾時 5s 未收到 av_end → 丟棄緩衝回 av_fail。
3. 顯示規則（與 watch-engineer 定案）：僅 CALLING 中收到且驗證通過 → 換下 T7 首字頭像；
   未接／斷線畫面維持首字；晚到（已 end）→ 仍回 ack av_end（傳輸本身成功）但不顯示；
   下一通來電 start 時清空頭像庫避免顯示前一個來電者。
4. 中止語義（與 watch-engineer 定案）：手機中止 → 不再送塊；手錶遇新 start/end/missed/BLE 斷線 →
   靜默丟棄緩衝（不回 av_fail，因手機同條件也會中止）；av_fail 僅用於 sha／missing／timeout。
5. 向前相容：v1 韌體收到 av_* 一律忽略（未知 t 規則）。

## 對 v1 規則的修訂
- v1「單一訊息 ≤200 bytes」：僅適用 v1 訊息；av_chunk 上限放寬為 500 bytes（≤MTU-3，實測 MTU 517）。

## 待批准後動作
- captain 批准 → 併入 docs/protocol.md 正式章節（v2 段落）＋ decisions.md 新增 D12。
- watch-engineer 依「手錶端行為」實作。
