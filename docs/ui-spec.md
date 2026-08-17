# UI 規格 v1.0（唯一 UI 權威文件）

## D17 銀河主題來電特效（2026-08-17 使用者裁決）
適用範圍：僅 CALLING（來電中）；MISSED 維持橙色、DISCONNECTED 維持灰。
- 配色：深空紫 #7C4DFF／靛藍 #536DFE／宇宙藍 #29B6F6／星雲青 #18FFFF／星雲洋紅 #E040FB；
  LINE 綠 #06C755 僅保留於副標「● 震動提醒中」品牌點綴。
- A 極光：AuroraView 改寫——三團柔光（紫下/青左/洋紅右，alpha ≤0.10）30s 繞屏中心緩轉（星雲流動）。
- C 光環隨震動節拍：ring 明滅與震動同步（亮相 140ms／熄 200ms；自訂節奏讀 pattern [on,off]，
  系統效果模式 700ms 循環近似）；外環正轉、內環反轉 30s/圈（sweep 漸層旋轉）。
- D17.8 文字配色/特效：CALLING 標題「LINE 來電/視訊來電」改 LINE 品牌綠（與白色名字區分）；
  副標「● 震動提醒中」改星雲青＋隨震動節拍閃爍（亮 1.0／暗 0.45，Activity 與 overlay 同步）。
- F 流動光環（D17.7，使用者：雙向彗星醜、參考網路類似效果、要自己看）：
  移除彗星頭/星點——純光三段：寬柔光帶 12dp（極淡銀河底光 0x1A）＋細亮環 2.5dp
  （銀河 sweep 全圈 0x66，40s 緩轉）＋流動光段 4dp（~110° 柔光波瓣、兩端平滑漸隱、
  亮青白，6s/圈「光在環上跑」）；3s 呼吸（alpha 0.5→0.85）；captain 以截圖像素分析
  自驗：光環帶亮度均勻、光段亮點清晰。
- 漣漪：RippleView 三圈改染紫/青/洋紅。
- 首字頭像底：CALLING 改 bg_avatar_galaxy（radial 靛→深紫）。
- 長名字：12sp 仍超出屏寬 70% → 固定 16sp＋marquee 橫向滾動（Activity 與 overlay 同步）。

設計原則：v1 只做「清楚、可用」，不做美化。所有螢幕依本文件實作，不得自行發散。
改 UI 一律先改本文件再改碼。雙端各自實作自己章節，共用 tokens 保持一致性。

## 設計 tokens
| token | 值 |
|---|---|
| 背景（手錶） | #000000 |
| 背景（手機） | 系統預設（淺色） |
| LINE 綠 | #06C755 |
| 主文字 | #FFFFFF |
| 次要文字 | #B0B0B0 |
| 警示色（未接） | #FF8A65 |

## 手機端（1 個畫面：MainActivity 設定頁）
- 頂部狀態列：「藍牙：已連線／掃描中／已斷線（手錶 OPP…15C）」
- 總開關：啟用 LINE 來電提醒
- 按鈕（LINE 綠）：測試來電／測試未接／停止測試（直送 BLE，供 T2/T4 驗收）
- 連結：授予通知存取權限（跳系統設定頁）
- 底部說明文字：看門狗 90s、震動節奏 [600,400]

## 手錶端（1 個 Activity，2 種狀態）
### IncomingCallActivity 來電狀態
- 全螢幕黑底；圓形安全區：內容限中央 70% 寬
- 上方：LINE 綠圓點（24dp）+「LINE 來電」20sp（視訊來電 →「LINE 視訊來電」）
- 中央：名字 40sp 粗體白字，自動縮放至 70% 寬；fallback「未知聯絡人」
- 下方：動態小字「● 震動提醒中」（LINE 綠）

### IncomingCallActivity 未接狀態（同 Activity 切換）
- 標題「LINE 未接來電」（警示色 #FF8A65）
- 名字同規格；副標「對方可能已掛斷」12sp 灰
- D15：看過一次原則——息屏即隱藏、抬腕不重顯；螢幕持續亮 → 8s 自動 finish 或右滑提前關；
  清除 KEEP_SCREEN_ON 允許自然息屏；來電中轉未接不重啟震動

### IncomingCallActivity 視覺強化（v2，docs/roadmap.md V2-2/V2-3/V2-4-v1；2026-08-17）
- 頭像（V2-4-v1）：名字首字圓形頭像（LINE 綠底 #06C755＋白字 28sp 粗體，64dp），置於標題上方；
  無名/空白 → 「?」；未來 T8 真實頭像到達後替換為照片。
- 脈動光圈（V2-3）：頭像外圈 LINE 綠環 84dp（stroke 3dp），alpha 0.35→0.9 呼吸循環（ObjectAnimator、
  約 1200ms/週期）；僅來電態循環，未接/斷線態靜止 alpha 0.35。
- 進場特效（V2-3）：頭像＋名字 scale 0.85→1.0（240ms）；標題/副標 alpha 0→1 淡入（300ms）。
  限制：ObjectAnimator 級別、無粒子、無複雜渲染（省電，手錶電池小）。
- 排版精修（V2-2）：頭像置頂；標題（原 20sp）於頭像下 14dp；名字 40sp 粗體距標題 18dp；副標 12sp 距名字 14dp；
  原「綠圓點」由頭像取代；圓形安全區 70% 不變。
- 未接/斷線態同步美化：頭像底改警示色 #FF8A65／灰 #B0B0B0，光圈同色靜止；文字色依 v1 原規格。
- overlay 備援路徑套用相同視覺（OverlayHelper 同構頭像＋光圈＋進場，CALL 態脈動）。
- 真實頭像（v2/T8，protocol.md v2 頭像傳輸）：來電中收到且 SHA-256 驗證通過 → 圓形裁切替換首字頭像
  （96×96 JPEG）；頭像晚到（已 end）→ 不顯示但仍寫入快取（仍回 ack av_end 免手機重試）；
  Activity 未在前台時頭像暫存記憶體（AvatarStore），來電畫面顯示前先查快取。

### 頭像快取（v3 擴充，T9 使用者回饋：首次傳輸 ~0.8s 延遲感）
- AvatarStore 由純記憶體升級為「internal storage 檔案快取＋name 映射」：
  files/avatars/<SHA-256(name) 前 16 hex>.jpg＋SharedPreferences JSON 索引（name→file/ts）。
- 上限：≤10 個名字、每張 ≤12KB；LRU（最久未用）淘汰；每張 JPEG q90 重壓寫入。
- CALLING 進入時：先查快取 → 命中直接顯示真頭像（零延遲，不等傳輸）；未命中 → 首字 → av_end 驗證通過後
  替換並寫入快取（覆寫同名字舊檔）。下一通同一來電者 → 秒顯真照片。
- 未接/斷線畫面：依名字查快取，有 → 真照片（無 tint）、無 → 首字（原「未接維持首字」規則作廢，改快取優先）。
- 頭像晚到（已 end）→ 不顯示但寫入快取（下一通秒顯）。

### IncomingCallActivity 視覺 v3（T9，使用者實測回饋；2026-08-17）
- 版面修正（實測：84dp 光環頂部被 466×466 圓屏邊緣切割）：光環 84dp→72dp、頭像 64dp→56dp；
  內容整體下移（頂部 padding 28dp）確保光環完整進入圓形安全區；垂直間距重新配比：
  光環→標題 12dp、標題→名字 16dp、名字→副標 12dp。
- 雙層光圈：外層光暈環 72dp（stroke 3dp，alpha 0.15→0.55 呼吸）＋內層實心環 60dp（stroke 2dp，
  反向相位 alpha 0.55→0.15）；1200ms/週期；未接/斷線態雙層靜止 alpha 0.25。
- 頭像外發光：頭像後方同色 radial 光暈（72dp，alpha 0.2→0.6 與外環同步呼吸）。
- 文字微光：標題/名字 alpha 0.85→1.0 微脈動（同週期、低對比）；副標不參與；進場淡入後 350ms 才啟動避免動畫打架。
- 背景 radial 綠光：全螢幕中央 radial gradient（#1406C755→透明）alpha 0.5→1.0 呼吸（非常 subtle 不搶主體）。
- 首字→真照片轉場：scale 0.9→1.0＋alpha 0.6→1.0 cross-fade 300ms（API 30 無 RenderEffect，
  模糊轉場留待 31+ 再啟用）。
- 限制沿用 v2：ObjectAnimator 級、無粒子、省電；未接/斷線態全部特效靜止為低 alpha 常數。
- 動態背景（T9 追加，使用者提出）：
  - 背景 radial 綠光暈改為慢速呼吸：alpha 0.05→0.15、3000ms/週期、與光圈呼吸（1200ms）相位錯開
    （起點取峰值、REVERSE）；未接/斷線態靜態 alpha 0.08。
  - LINE 綠波浪：自繪 View（Canvas＋ValueAnimator）2~3 條半透明正弦波紋自下而上緩慢流動
    （~9s/週期、alpha ≤0.14、stroke 2dp）；僅來電態繪製與動畫，未接/斷線停止繪製。
  - 原則：背景是氛圍、前景是主體，不喧賓奪主；純 AOSP。
- overlay 備援路徑套用相同視覺（雙層光圈＋發光＋背景 radial 慢呼吸＋波浪＋文字微光；轉場用 alpha cross-fade）。

### IncomingCallActivity 視覺 v3.1（T9 使用者視覺回饋；2026-08-17）
- 頭像區再下移：頂部 padding 28→40dp、光環 72→68dp、頭像 56→52dp（字級 24sp）、垂直間距 10/12/10dp
  → 光環頂部距圓屏邊緣 ≥60px 視覺間隙。
- 背景特效重設計（取代三條正弦波）：
  a. 擴散漣漪（主效果）：自頭像中心向外擴散的 2~3 圈圓環（scale 0.6→1.3、alpha 0.35→0、
     ~2.4s 循環、相位錯開 800ms）——雷達脈衝風格，乾淨現代；僅 CALLING 運行。
  b. 極光光暈（背景）：底部上來的柔和大面積 radial 綠光（中心於屏高 78%、半徑 75% 屏寬、
     色 alpha ≤0.10）緩慢呼吸 3.5s/週期（view alpha 0.5→1.0）；未接/斷線靜態低 alpha 0.5。
  c. 原中央 radial 綠光層與波浪移除；雙層光圈／發光／文字微光／進場沿用 v3。
- overlay 備援同視覺；純 AOSP（Canvas＋ValueAnimator/ObjectAnimator）。

### IncomingCallActivity 視覺 v3.2（T9 圓形可視區安全收斂；2026-08-17）
- 螢幕事實：466×466@320dpi、FLAG_ROUND → 可視區＝內切圓（半徑 116.5dp），邊角越出圓外物理不可見。
- 內容欄再下移：paddingTop 56dp、垂直間距 8/10/8dp → 光環頂 y≈45.5dp≈91px（≥88px 目標）。
- 漣漪收斂：RippleView 改為全螢幕背景層（中心＝頭像中心），canvas clipPath 限制於
  「頭像中心、半徑 min(96dp, 內切圓半徑−中心偏移)≈79.5dp」的圓；漣漪 scale 0.6→1.05
  （最大半徑 ≈71.4dp）→ 任何擴散弧線皆在可視邊緣內淡出。
- 極光收斂：AuroraView canvas clipPath＝螢幕內切圓（半徑 116.5dp），底部光暈不滲出圓屏外緣。
- 全元素安全檢查（內切圓半徑 108dp 目標內）：雙環/發光半寬 34dp@y79.5dp（可視半寬 110.5dp）、
  名字半寬 ≤81.5dp（可視 ≈116.4dp）→ 全部在可視區內。
- 斷線態圖示（v3.2 追加，使用者回饋：斷線顯示來電者頭像很奇怪）：DISCONNECTED 頭像區改顯示
  ic_disconnect_kawaii（64dp 灰圓＋斷訊弧線＋×_× 眼＋小嘴，kawaii 故障風；視圖 52dp 縮放），
  不查 AvatarStore、不顯示首字；MISSED 維持快取頭像；標題「藍牙已斷線」與副標不變。
- overlay 同規則；純 AOSP（Canvas＋clipPath＋ValueAnimator/ObjectAnimator）。

### IncomingCallActivity 視覺 v3.4（T9 使用者要求星空背景；2026-08-17）
- 星空主背景：StarfieldView（Canvas＋ValueAnimator，純 AOSP）取代極光呼吸成為主背景：
  - 40~50 星點（1~2px、白/淡綠混色、alpha 0.2~0.8），正弦 twinkle（週期 1.5~4s 隨機）＋
    極緩慢上漂（~0.3px/幀，漂出安全圓即於底部重生成）
  - 星點僅分布於內切圓安全區（clipPath），中央內容區（頭像/標題/名字）留白不擁擠
  - 僅 CALLING 運行；end/未接/斷線靜止為極淡靜態星空（alpha 0.15）
- 漣漪同心（v3.3 方案 A）：RippleView 為 ringContainer 首位子層（Gravity.CENTER、160dp），
  圓心＝畫布中心＝頭像中心（Activity/Overlay 同構，零座標換算）。
- 極光層移除；雙層光圈／發光／文字微光／進場沿用；overlay 同視覺。

### IncomingCallActivity 視覺 v3.5（T9 使用者三項回饋；2026-08-17）
- 漣漪同心（v3.3 方案 A，結構性保證）：RippleView 為 ringContainer 首位子層（Gravity.CENTER、160dp、
  clipChildren=false），圓心＝畫布中心＝頭像中心；無座標換算。（Activity/Overlay 同構）
- MISSED 幾何與 CALL 完全一致（同一容器/布局，僅文字與顏色切換）：paddingTop 56dp、光環 68dp 全在安全圓內。
- DISCONNECTED 完全關閉來電特效：雙環／發光／星空／漣漪 visibility=GONE（無動畫、無靜態殘留），
  僅顯示 kawaii 斷線圖示＋標題＋副標；自 DISCONNECTED 轉回 CALL/MISSED 時恢復可見。
- 星空主背景（v3.4）：StarfieldView 40~50 星點 twinkle＋緩慢漂移、clipPath 安全圓內、僅 CALLING 運行。
- 斷線圖示素材後續由 captain 替換（接線與版面保留）。

### IncomingCallActivity 視覺 v3.6（T9 佈局重平衡＋方形殘影排查；2026-08-17）
- 幾何對齊式布局（取代 gravity=CENTER 溢出模式）：內容欄頂部對齊（center_horizontal），
  頭像＋光環圓心固定於屏高 42%（≈98dp，光環頂＝64dp）；下方依序：標題（光環底+14dp）、
  名字（+10dp）、副標（+8dp）；三個 TextView 均 includeFontPadding=false。
- 方形殘影排查：所有可見 drawable 皆 oval（bg_glow/ring/ring_inner/avatar 已核對）；矩形
  bg_radial_bg 已零引用；RippleView/StarfieldView 加顯式透明背景防硬體渲染 dirty-region 殘影。
- 漣漪同心（方案 A）、星空主背景、斷線態無特效沿用 v3.3~v3.5。

### IncomingCallActivity 視覺 v3.7（T9 整體上移；2026-08-17）
- 內容欄 paddingTop 64→52dp（頭像圓心 98→86dp ≈ 屏高 37%）。
- 漣漪基半徑 68→62dp（scale 0.6→1.05 → 最大 65.1dp；漣漪頂 ≈21dp≈42px 與圓屏邊緣保持安全距離）。
- clipPath 公式不變（min(96dp, 內切圓−偏移) 自動收斂）；三態同幾何、同心、星空、魔法少女圖示沿用。

### IncomingCallActivity 視覺 v3.8（T9 繼續上移；2026-08-17）
- 內容欄 paddingTop 52→44dp（頭像圓心 86→78dp ≈ 屏高 33.5%）。
- 漣漪基半徑 62→58dp（最大 60.9dp；漣漪頂 ≈17.1dp≈34px 保住頂部安全距離）。
- clipPath 公式不變（自動收斂 min(96, 116.5−38.5)=78dp）；三態同幾何、同心、星空、魔法少女圖示沿用。

### IncomingCallActivity 視覺 v3.9（T9 再上移 16dp；2026-08-17）
- 內容欄 paddingTop 44→28dp（頭像圓心 78→62dp ≈ 屏高 26.6%）。
- 漣漪基半徑 58→48dp（最大 50.4dp；漣漪頂 ≈11.6dp≈23px 保住頂部安全距離）。
- clipPath 公式不變（自動收斂 min(96, 116.5−54.5)=62dp；漣漪最大 50.4dp < 62dp → 弧線安全）。
- 三態同幾何、同心、星空、魔法少女圖示沿用。

### IncomingCallActivity 視覺 v3.10（T9 下滑關閉手勢；2026-08-17）
- 來電態（Activity）：下滑（down→up dy≥80px 且 |dx|<dy×0.5）→ 等同 watch 端 endCall(false)：
  停震＋關畫面；**不送 BLE 指令**（手機端來電不受影響）。
- 未接/斷線態（Activity/overlay）：右滑 → 立即 finish；螢幕持續亮時 8s 自動 finish（D15）。
- Overlay 路徑：root onTouchListener 下滑 → OverlayHelper.dismiss()＋dismissListener 回呼 →
  service endCall(false) 停震（service onCreate 註冊、onDestroy 清除）。
- logcat：{"t":"swipe_dismiss","src":"activity|overlay"}。

### IncomingCallActivity 視覺 v3.11（T9 下滑拖動視窗效果；2026-08-17）
- Activity：DOWN 攔截記 startY；MOVE 內容視窗 translationY=dy（≥0；dy>160px 後 0.6 阻尼）、
  alpha 1→0.25（dy 0→160px 對應）；UP：dy≥80px 且 |dx|<dy×0.5 → 飛出（translationY→屏高＋alpha→0，
  180ms）→ finish＋來電態轉交 endCall(false)；dy<80px 或多指/橫向干擾 → 彈回（200ms Decelerate＋alpha 1）。
- Overlay：gravity 改 TOP、y=0；MOVE 以 wm.updateViewLayout 移動 params.y（阻尼同上）；UP ≥80px →
  飛出動畫 y→屏高（180ms）→ dismiss＋dismissListener；否則回彈 y=0。
- logcat swipe_dismiss 照舊；ViewPropertyAnimator／ValueAnimator 純 AOSP。

### IncomingCallActivity 視覺 v3.12（T9 手勢改右滑配合系統關閉動畫；2026-08-17）
- Activity：拖動軸改 X——MOVE translationX=dx（≥0；dx>160px 後 0.6 阻尼）、alpha 1→0.25（dx 0→160px）；
  垂直干擾 |dy|>dx×0.5 → 回彈。UP：dx≥80px 且無垂直干擾 → **直接 finish()（不加自訂飛出，讓系統右滑
  關閉過場自然接續）**；未達閾值 → 彈回 translationX=0。下滑（dy）不再觸發關閉。
- Overlay：同邏輯 X 軸（updateViewLayout params.x 跟手）；UP 達標 → 自訂 180ms x→屏寬飛出 →
  dismiss＋dismissListener（overlay 無系統動畫，保留自訂飛出）；否則回彈 x=0。
- logcat swipe_dismiss 照舊；右滑＝關閉顯示。

### IncomingCallActivity 視覺 v3.13（T9 右滑拖動跟手性優化；2026-08-17）
- Activity：DOWN → 根視圖 setLayerType(HARDWARE)（快取 GPU 紋理，translationX 變純 GPU 位移）＋
  暫停星空/漣漪（stop＋記 wasRunning）；MOVE 僅設 translationX/alpha；UP 彈回動畫結束 →
  LAYER_TYPE_NONE＋CALL 態恢復星空/漣漪；達標直接 finish（圖層隨 Activity 釋放）。
- Overlay：DOWN → frame 根 HARDWARE 圖層＋暫停動畫；MOVE updateViewLayout 節流（dx 變化 ≥8px
  或 ≥16ms 才呼叫）；UP 回彈結束 → NONE＋CALL 態恢復；飛出路徑不變。
- 其餘行為、log、操作說明不變。

### IncomingCallActivity 視覺 v3.14（T9 拖動黑邊修復；2026-08-17）
- 新增 translucent 主題 Theme.WatchCall.Translucent（衍生自 Theme.WatchCall：windowIsTranslucent=true、
  windowBackground=透明）；IncomingCallActivity 於 Manifest 指定此主題。
- 根布局仍全黑 → 初始全黑；右拖時左側露出透明 windowBackground → 看到桌面（黑邊消失）。
- showWhenLocked/turnScreenOn 保持；若實測 translucent 下亮屏異常 → fallback 改 windowBackground 半透明黑 80%。
- overlay 本就透明無需改。

### IncomingCallActivity 視覺 v3.15（T9 拖動卡片改圓形；2026-08-17）
- 新增 drawable/bg_round_black.xml（shape=oval、solid #000000）。
- Activity 根 FrameLayout 背景改 @drawable/bg_round_black；Overlay frame 背景改 setBackgroundResource 同 drawable。
- 效果：初始全屏仍黑（實體圓形可視區內整圓覆蓋）；右拖時滑動的是一張圓形黑卡、角落透明（配合 translucent 主題露桌面）。

### SettingsActivity v3.17（T9 震動模式重設計；2026-08-17）
- 移除「自訂節奏」作為模式選項；新增 Switch「使用系統震動效果」（預設 OFF＝自訂節奏）。
- Switch ON → 顯示 4 個系統效果 RadioGroup（短按 CLICK 預設／雙擊 DOUBLE_CLICK／滴答 TICK／重擊 HEAVY_CLICK）；OFF → 隱藏。
- Prefs：vib_mode 語意調整（""＝自訂；其餘＝系統效果）＋use_system_effect bool。
- 預覽震動：點選系統效果／節奏／強度任一選項 → 立即單次預覽（系統效果 createPredefined 一次；
  自訂 600ms 單次依強度）；CALLING 中跳過預覽（VibratorController companion anyCalling 跨實例判斷）。
- 來電震動邏輯不變（系統效果 700ms 循環／自訂波形循環）。

### SettingsActivity v3.16（T9 震動模式擴充；2026-08-17）
- 新增「震動模式」選項群（垂直 RadioGroup，與自訂節奏並列）：
  自訂節奏（現有急促/適中/長震生效）｜短按 CLICK｜雙擊 DOUBLE_CLICK｜滴答 TICK｜重擊 HEAVY_CLICK。
- Prefs 新增 vib_mode；VibratorController 來電震動：custom → 現有波形；系統效果 →
  VibrationEffect.createPredefined 單次效果以 700ms 週期重發循環（CALLING 期間），end/cancel 停止；
  重入保護維持；missed 仍 [300,200,300] 不變。
- 註記：ColorOS 手錶系統完整震動效果清單無公開 API 可列舉；僅 Android 公開預定義效果（API 29+，本錶 30）可用。

### 系統行為 v3.19（T9 ColorOS 凍結自癒；2026-08-17）
- BlePeripheralService 加 AlarmManager 每 60s 喚醒自檢（setExactAndAllowWhileIdle、ELAPSED_REALTIME_WAKEUP；
  API 31+ 無 exact 權限時降級 setAndAllowWhileIdle）：無連線且廣告非 ADVERTISING/STARTING → 重啟廣告；
  GattServer 不存在 → 重建；logEvent {"t":"keepalive_check","connected":bool,"advertising":bool}。
- 連線中僅 debug log 不動作；每次觸發後重新排程（單次 alarm 循環）。
- manifest：KeepAliveReceiver（exported=false）＋SCHEDULE_EXACT_ALARM 權限（本錶 API 30 不需但宣告無害）。
- 實測目標：凍結後 1 分鐘內自癒重連。

### SettingsActivity v3.18（T9 右滑關閉，與來電畫面一致；2026-08-17）
- Manifest 改 Theme.WatchCall.Translucent；根布局背景 @drawable/bg_round_black（圓形黑卡）。
- 右滑拖動同 IncomingCallActivity v3.13：DOWN 不攔截（ScrollView 垂直滾動正常）；MOVE 判定
  dx>32px 且 dx>|dy|×1.2 → 開始攔截（HARDWARE 圖層＋translationX 跟手＋alpha 1→0.25、>160px 阻尼 0.6）；
  UP dx≥80px 且 |dy|<dx×0.5 → 直接 finish（系統右滑過場接續）；否則彈回（200ms Decelerate、結束移除圖層）。
- logcat：{"t":"swipe_dismiss","src":"settings"}。右滑＝關閉（來電與設定畫面皆支援）。

### SettingsActivity（v2，使用者 2026-08-17 點名需求；含應用選單入口）
- 加 MAIN/LAUNCHER intent-filter → 手錶應用選單顯示「LINE 來電提醒」（icon 既有）
- 畫面內容（黑底、LINE 綠 accent、圓形安全區）：
  1. 頂部：藍牙狀態（已連線/待機中，顯示手錶 OPP…15C）
  2. 震動強度：三檔（弱 100／中 150 預設／強 200；存 SharedPreferences，VibratorController 即時讀取）
  3. 震動節奏：急促 [300,150]／適中 [600,400] 預設／長震 [1000,300]
  4. 測試按鈕：測試來電／測試未接／停止（走既有 debug 深連結路徑）
  5. 底部：開機自啟說明一行（灰 12sp）

## 圓形螢幕注意
- 全部 wrap_content 置中，勿用邊角按鈕
- 文字最大寬度 = 螢幕寬 70%；超出自動縮字（autosize）
