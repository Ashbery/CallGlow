# 產品路線圖（Roadmap）

> 使用者 2026-08-17 提出 v2 方向。排程原則：T4 功能測試 → T5 穩定化 → v2 體驗優化。
> 實作前會先更新 ui-spec.md / protocol.md，再動程式碼。

## v2 願望清單（依使用者需求，附可行性評估）

### V2-1 手錶端設定畫面＋應用選單入口（優先）
現況：手錶 App 無 LAUNCHER Activity（純後台設計），所以應用選單看不到它。
方案：新增 SettingsActivity（MAIN/LAUNCHER，icon 已有）→ 應用選單出現「LINE 來電提醒」。
設定項目：震動強度（幅度 100~255）、震動節奏（急促/適中/長震）、測試按鈕（來電/未接/停止）、
藍牙連線狀態、開機自啟說明。
可行性：✅ 小工程（manifest＋新畫面）。與 T5「震動模式選項（選配）」合併。
負責：watch-engineer；規格先更新 ui-spec.md。

### V2-2 來電介面美化
方向：LINE 綠品牌視覺強化、名字排版與間距、圓形安全區細節、狀態指示精緻化。
可行性：✅ 純 UI 層。規格先更新 ui-spec.md（v2 章節）。

### V2-3 介面特效
方向（輕量、省電為前提）：脈動光圈（alpha 動畫）、圖示縮放進場、文字淡入。
限制：避免粒子/高耗電特效；手錶電池小，特效以 ObjectAnimator 級別為限；實機驗流暢度。
可行性：✅（純 AOSP android.animation，無 GMS）。

### V2-4 來電人頭像
兩級方案：
- v1（免傳輸）✅：姓名首字圓形頭像（LINE 綠底＋名字第一字）。零協議變更、零延遲。
- v2（真實照片）⚠️ 需評估：手機端 READ_CONTACTS 權限 → 依顯示名比對通訊錄頭像 →
  壓縮 ~96×96 JPEG → BLE 分塊傳輸（MTU 517 每塊 ~500B，約 20~40 塊，估計 1~3 秒）。
  限制：LINE 通知只給「名字」不給電話號碼，比對靠模糊匹配（同名風險）；LINE 好友頭像本身
  不開放給第三方（無法直接取得）。
  決策點：先做 v1 首字頭像；v2 依 BLE 分塊傳輸實測穩定性再定（需 protocol v2 新增頭像分塊指令）。

## 排程
1. T4 收尾（接聽即停＋5s 未接補償重測）
2. T5 穩定化（含 V2-1 合併：震動設定畫面＋應用選單入口）
3. V2-2 / V2-3 美化＋特效（同一輪做，watch-engineer）
4. V2-4-v1 首字頭像（小改，可與 V2-2 同輪）
5. V2-4-v2 真實頭像（獨立評估後決定）

## T8 執行中（2026-08：V2-4-v2 定案開跑）
- 關鍵發現（T4/T5 實測）：LINE 來電通知的 largeIcon 直接攜帶對方頭像（156×156，Incoming/Ongoing 皆有）→
  無需 READ_CONTACTS／名稱比對（原 V2-4-v2 的可行性疑慮作廢）。
- 定案方向：來電時手機取 largeIcon → 96×96 JPEG（q80，>12KB 降 60/40）→ BLE 分塊（339B/塊，b64 ≤500B 訊息，
  WRITE_NO_RESPONSE 節奏 8ms）→ 手錶 SHA-256 驗證後換下 T7 首字頭像；傳輸 ≤3s、不阻塞 start/end。
- 協議：docs/protocol-v2-avatar-draft.md（待 captain 批准後併入 protocol.md＋decisions D12）。
- 手機端：AvatarTransfer（純邏輯）／AvatarCompressor／BleCentralService session／listener 整合——已實作；
  手錶端由 captain 另派 watch-engineer。
