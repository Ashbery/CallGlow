# watch-app — 手錶端（com.linewatch.watch）

LINE 來電持續提醒系統的手錶端 App：BLE Peripheral（Nordic UART Service，FGS 常駐），
收到手機端指令後「持續震動＋全螢幕顯示來電者」，來電結束／未接／看門狗皆自動收尾。
純 AOSP（minSdk 30 / compileSdk 34 / targetSdk 34），禁用 androidx.wear 與 GMS（docs/decisions.md D6/D7）。

> 圖示素材：斷線圖示 ic_disconnect_kawaii.png 為使用者提供之原創圖片（已去背處理，256×256）。

## logcat 慣例（docs/test-plan.md v2.0 §0，測試驗收全靠 logcat）
- tag 固定 **LineWatchWatch**
- 關鍵事件（start／end／ping／pong／connect／disconnect／watchdog）輸出**單行完整 JSON**
- 收 log：`adb -s %WT% logcat -s LineWatchWatch:* > watch.log`（%WT% 見下）

## 元件（src/main/java/com/linewatch/watch/）

| 檔案 | 職責 |
|---|---|
| BlePeripheralService | FGS(connectedDevice)：openGattServer＋advertise LOW_LATENCY；收 CHAR_CMD → 狀態機 → 啟動/關閉 Activity＋震動；CHAR_STATE notify ack/pong；斷線提示；v1.1 missed 補送（RINGING→end(missed=true)、IDLE→未接畫面 8s 不震動，回 ack type="missed"） |
| IncomingCallActivity | 全螢幕黑底＋KEEP_SCREEN_ON；來電／未接（8s 自動關）／斷線，同一 Activity 狀態切換；v2 視覺：首字頭像＋脈動光圈＋進場縮放/淡入（ObjectAnimator，省電） |
| VibratorController | [600,400] 循環；end cancel；未接 [300,200,300] 單次；重入保護 |
| Watchdog | 120s 無 end → 自動視為未接（D3），log 事件 {"t":"watchdog",...} |
| Protocol | NUS UUID、指令解析（JSON）、ack/pong 建構、名稱 60-byte 截斷、logcat 慣例（LOG_TAG/logEvent） |
| BootReceiver | 開機自動啟動前台服務（D10 定案保留，captain 裁決；配合 test-plan §5.7 重開機恢復） |
| SettingsActivity | v2 設定畫面（ui-spec V2-1）：MAIN/LAUNCHER 應用選單入口；藍牙狀態列、震動強度三檔（弱100/中150預設/強200）、節奏三檔（急促/適中/長震）、測試按鈕、開機自啟說明 |
| Prefs | 震動強度/節奏 SharedPreferences 持久化；VibratorController 即時讀取（取代硬編碼 150） |
| OverlayHelper | Activity 背景啟動受限時的 SYSTEM_ALERT_WINDOW 備援（需使用者授權，未授權靜默；P7 判讀受限才啟用） |

### debug 變體專用（src/debug/；release 不含任何測試 receiver）

| 檔案 | 職責 |
|---|---|
| DebugEndReceiver | adb broadcast 模擬手機送 end（走與 BLE end 相同狀態機） |
| BgStartTestReceiver | P7 背景啟動（BAL）隔離測試（docs/probe-report.md P7） |

## 建置（由使用者執行）

本機無 gradle-wrapper.jar：用 Android Studio 開啟 **watch-app/**（AS 會自動補齊 wrapper），
選 Build → Build APK(s)。或命令列（若本機 PATH 有 gradle）：

    cd watch-app
    gradle assembleDebug

APK 輸出：watch-app/app/build/outputs/apk/debug/app-debug.apk
（local.properties 已寫入 sdk.dir=C:\Users\<使用者>\AppData\Local\Android\Sdk，路徑不同請自行修正）

> **相容性（docs/probe-report.md P1）**：手錶 OWW261 為 32-bit 系統
> （ro.product.cpu.abilist = armeabi-v7a,armeabi，無 arm64）。
> 本專案純 Kotlin、無原生庫、未設 ndk.abiFilters → 預設 APK 已含 armeabi-v7a，可直接安裝；
> **切勿**在 app/build.gradle.kts 加入排除 armeabi-v7a 的 abiFilters。

## 安裝與啟動（%WT% = 手錶序號 %WT%，USB 連線；docs/probe-report.md P0）

    set WT=%WT%
    adb -s %WT% install -r watch-app\app\build\outputs\apk\debug\app-debug.apk
    adb -s %WT% shell am start-foreground-service -n com.linewatch.watch/.BlePeripheralService

手錶端無需 pm grant（Android 11：BLUETOOTH／BLUETOOTH_ADMIN 為 normal 權限，見 test-plan §3.1）。
開機後 BootReceiver 會自動啟動服務；首次安裝可用上面第三行立即生效。

### ⚠️ 必做：ColorOS 凍結防護白名單（v3.20，docs/decisions.md D13）
實測根因：螢幕關閉後 ColorOS Watch 的 BmPowerManager 會凍結第三方 App（BLE 斷線、重連要 4–16 分鐘），
且 WearFrw 封鎖第三方 AlarmManager 喚醒。修法＝把本 App 加入系統 AOD 白名單（實測 8+ 分鐘螢幕關閉連線不中斷）：

    adb -s %WT% shell appops set com.linewatch.watch WRITE_SETTINGS allow
    adb -s %WT% shell settings put global aod_support_apps "%(settings get global aod_support_apps),com.linewatch.watch"

（把 %(...) 換成實際取得的值後再整行執行；受保護鍵第三方 App 無法自寫 → 必須 adb 一次性寫入，跨重啟持久。）
aod_switch_apps 由 App 開機時自癒（v3.20 BootReceiver → ColorOsWhitelist.ensure）；
aod_switch_all_apps 開機時會被系統重建，實測非防凍結所需。

### 若重開機後服務未自動啟動（中國版 ColorOS Watch 自啟動白名單）
中國版 ColorOS Watch 可能有「自啟動管理」限制擋掉 BOOT_COMPLETED。請在手錶上：
設定 → 應用程式管理 → LINE 來電提醒（LineWatch）→ 允許「自啟動」／「後台運行」
（實際路徑以實機選單為準）。設定後重開機再確認：
`adb -s %WT% shell dumpsys activity services com.linewatch.watch` 應列出 BlePeripheralService 且 isForeground=true。

## T3 測試指令（docs/test-plan.md v2.0 第 3 章；另開視窗收 log）

1. 模擬來電（震動＋畫面＋120s 看門狗，§3.2/§3.3）：
   adb -s %WT% shell am start -n com.linewatch.watch/.IncomingCallActivity --es name 測試 --ez missed false
   視訊來電：同上，再加 --es kind video
2. 模擬停止（等同 BLE end；ColorOS 擋 shell 廣播 → 以深連結 extra 轉交，主要路徑）：
   adb -s %WT% shell am start -n com.linewatch.watch/.IncomingCallActivity --ez debug_end true --ez missed false
3. 模擬未接 end：
   adb -s %WT% shell am start -n com.linewatch.watch/.IncomingCallActivity --ez debug_end true --ez missed true
   （次要路徑，部分裝置可用：adb -s %WT% shell am broadcast -a com.linewatch.watch.action.DEBUG_END --ez missed true）
4. 直接測試未接畫面（8s 自動關閉＋[300,200,300] 單次震動，§3.2）：
   adb -s %WT% shell am start -n com.linewatch.watch/.IncomingCallActivity --es name 測試 --ez missed true
5. 看門狗：執行 1 後等待 120s → logcat 出現 {"t":"watchdog"...} → 自動轉「LINE 未接來電」並於 8s 後關閉（§3.3）
6. 斷線提示（§3.4）：非震動狀態下手機關藍牙 → 手錶顯示「藍牙已斷線」（8s 自動關）；震動中斷線 → 震動持續，由 120s 看門狗收尾
7. P7 背景啟動隔離測試（§3.5，僅 debug 建置）：
   adb -s %WT% shell am broadcast -a com.linewatch.watch.action.TEST_BG_START --es name 背景測試
   判讀：畫面出現且 logcat result=ok_visible → 背景啟動豁免有效；
   result=blocked_or_timeout 或系統 blocked 訊息 → BAL 受限。
   ⚠️ 已知結果（2026-08-17 實測）：本手錶 BAL「受限」，預期 blocked_or_timeout 屬正常，
   overlay 備援已啟用（見測試指令 10），無需再回報。
8. FGS 常駐確認（§3.6）：adb -s %WT% shell dumpsys activity services com.linewatch.watch → isForeground=true
10. overlay 備援（P7 實測 BAL 受限 → 螢幕未亮時改走 SYSTEM_ALERT_WINDOW overlay 顯示三態）：
    授權：adb -s %WT% shell appops set com.linewatch.watch SYSTEM_ALERT_WINDOW allow
    （或手錶設定 → 應用程式管理 → LINE 來電提醒 → 允許「顯示在其他應用程式上層」）
    驗證：關螢幕狀態下執行測試 1 → 約 1.5s 後 overlay 顯示來電畫面（Activity 路徑被系統 Abort）
11. 使用者級解法（選配）：手錶設定 → 應用程式管理 → LINE 來電提醒 若有「允許後台啟動」開關 → 允許，
    可讓 Activity 路徑在背景也生效（以實機選單為準）
9. 震動成對啟停（§3.2）：來電中轉未接 → 不重啟震動；adb -s %WT% shell am force-stop com.linewatch.watch → 震動全停

## T9 v3.19 凍結自癒（ui-spec v3.19：ColorOS 凍結後 1 分鐘內恢復）
1. AlarmManager 每 60s 喚醒（setExactAndAllowWhileIdle／無權限降級 inexact）→ keepalive_check 自檢
2. 無連線且廣告非 ACTIVE → 重啟廣告／重建 GattServer；連線中僅 debug log
3. logcat {"t":"keepalive_check","connected":bool,"advertising":bool}
4. 實測：等 2 分鐘自然凍結 → 1 分鐘內自癒重連（手機端 log connected）；60s 週期 RTC 喚醒耗電極低

## T9 v3.18 操作說明（ui-spec v3.18：設定畫面右滑關閉）
1. SettingsActivity：右滑（dx≥80px、無垂直干擾）→ 直接 finish（系統右滑過場接續）；未達閾值彈回
2. 垂直滾動不受影響：DOWN 不攔截、MOVE 判定 dx>32px 且 dx>|dy|×1.2 才接管拖動
3. translucent 主題＋圓形黑卡 → 拖動露桌面無黑邊；logcat {"t":"swipe_dismiss","src":"settings"}
4. 右滑＝關閉（來電與設定畫面皆支援）

## T9 v3.17 驗證（ui-spec v3.17：震動模式重設計）
1. Switch「使用系統震動效果」OFF（預設）＝自訂節奏＋強度；ON → 顯示 4 系統效果（短按預設）
2. 點選任一選項（系統效果/節奏/強度）→ 立即單次預覽震動；CALLING 中自動跳過預覽
3. 來電邏輯不變：系統效果 700ms 循環／自訂波形循環；missed 仍 [300,200,300]
4. Prefs：vib_mode（""＝自訂）＋use_system_effect bool；logcat {"t":"settings","key":"use_system_effect|vib_mode",...}
5. ⚠️ 限制註記不變：ColorOS 完整震動效果清單無公開 API

## T9 v3.16 驗證（ui-spec v3.16：震動模式擴充）
1. Settings 新增「震動模式」：自訂節奏（現有）／短按 CLICK／雙擊 DOUBLE_CLICK／滴答 TICK／重擊 HEAVY_CLICK
2. 系統效果＝VibrationEffect.createPredefined（API 29+）單次效果以 700ms 週期重發循環（CALLING 期間）；end 停止
3. missed 仍 [300,200,300]；重入保護維持；logcat {"t":"settings","key":"vib_mode",...}
4. ⚠️ 限制註記：ColorOS 手錶系統完整震動效果清單無公開 API 可列舉；僅 Android 公開預定義效果可用
5. 驗證：切換模式 → dumpsys vibrator 確認效果；來電震動依所選模式變化

## T9 v3.12 操作說明（ui-spec v3.12：右滑關閉，配合系統動畫）
1. 來電畫面：按住右滑 → 視窗跟手右移＋alpha 漸降；鬆手 ≥80px → 直接 finish（系統右滑關閉過場自然接續）；未達閾值/垂直干擾 → 彈回
2. overlay 路徑：同 X 軸跟手（params.x），≥80px → 自訂 180ms 飛出 → dismiss＋停震
3. 下滑不再觸發關閉；logcat {"t":"swipe_dismiss","src":"activity|overlay"}

## T9 v3.11 操作說明（ui-spec v3.11：下滑拖動視窗）
1. 來電畫面：按住下滑 → 視窗跟手移動＋alpha 漸降；鬆手 ≥80px → 飛出關閉（停震）；<80px → 彈回
2. overlay 路徑：同樣拖動視覺（wm.updateViewLayout 移動 params.y），≥80px 飛出 → dismiss＋停震
3. 多指/橫向干擾 → 彈回；logcat {"t":"swipe_dismiss","src":"activity|overlay"}

## T9 v3.10 操作說明（ui-spec v3.10：下滑關閉手勢）
1. 來電畫面：下滑（≥80px 垂直、|水平|<垂直×0.5）→ 停震＋關畫面（本地行為，不影響手機端來電）
2. 未接/斷線：下滑 → 立即關閉（取消 8s 自動關）
3. overlay 路徑：下滑 → dismiss＋service endCall(false) 停震
4. logcat：{"t":"swipe_dismiss","src":"activity|overlay"}

## T9 v3.9 驗證（ui-spec v3.9：再上移 16dp）
1. paddingTop 28dp → 頭像圓心 62dp（屏高 26.6%）；漣漪基半徑 48dp（最大 50.4dp、頂部 ≈23px 安全距離）
2. clipPath 自動收斂 min(96, 116.5−54.5)=62dp > 漣漪最大 50.4dp → 弧線安全
3. 三態同幾何、同心、星空、魔法少女圖示、nullable 全保持

## T9 v3.8 驗證（ui-spec v3.8：繼續上移）
1. paddingTop 44dp → 頭像圓心 78dp（屏高 33.5%）；漣漪基半徑 58dp（最大 60.9dp、頂部 ≈34px 安全距離）
2. clipPath 自動收斂 min(96, 116.5−38.5)=78dp；三態同幾何、同心、星空、魔法少女圖示、nullable 全保持
3. 驗證：上移後漣漪頂部仍不被切、未接/斷線同位置

## T9 v3.7 驗證（ui-spec v3.7：整體上移）
1. paddingTop 52dp → 頭像圓心 86dp（屏高 37%）；漣漪基半徑 62dp（最大 65.1dp，頂部距圓屏邊緣 ≈42px）
2. 三態同幾何、同心、星空、魔法少女圖示、斷線無特效、nullable 修正全保持
3. 驗證：整體上移後漣漪頂部仍不被切、上下留白均衡、未接/斷線同位置

## T9 v3.6 驗證（ui-spec v3.6：幾何對齊布局＋方形殘影排查）
1. 幾何對齊：頭像圓心＝屏高 42%（98dp）、光環頂＝64dp；標題/名字/副標依序 +14/+10/+8dp；includeFontPadding=false
2. 內容欄頂部對齊（gravity=center_horizontal＋paddingTop 64），不再用 CENTER 溢出模式
3. 方形殘影：可見 drawable 全 oval 核對；矩形 bg_radial_bg 零引用；RippleView/StarfieldView 顯式透明背景
4. 驗證：頭像上方空白縮減、下方文字不再擠出、漣漪無上切；modlens 判讀

## T9 v3.5 驗證（ui-spec v3.5：斷線完全關特效＋同心/星空/MISSED 幾何確認）
1. DISCONNECTED：雙環/發光/星空/漣漪 visibility=GONE（無動畫無靜態殘留），僅 kawaii 圖示＋標題＋副標
2. CALL/MISSED：特效層恢復可見（MISSED 靜態低 alpha）
3. 漣漪同心（方案 A）與星空（v3.4）已在位；MISSED 幾何與 CALL 同一容器（paddingTop 56/光環 68）
4. 斷線圖示素材待 captain 替換（接線保留）

## T9 v3.4 驗證（ui-spec v3.4：星空主背景＋漣漪同心）
1. 星空：40~50 星點（白/淡綠、twinkle 1.5~4s、上漂 ~0.3px/幀）分布於內切圓內、中央內容區留白
2. 僅 CALLING 運行；未接/斷線靜止為極淡靜態星空（alpha 0.15）；極光層已移除
3. 漣漪同心（v3.3 方案 A）：RippleView 為 ringContainer 首位子層、圓心＝畫布中心
4. 連拍兩張看星點相位差與漂移；確認主體（頭像/名字）清晰不被星空干擾
5. overlay 同視覺；nullable 修正完好

## T9 v3.2 驗證（ui-spec v3.2：圓形可視區安全收斂）
1. 內容欄再下移：paddingTop 56dp、間距 8/10/8dp → 光環頂 y≈45.5dp≈91px（≥88px）
2. 漣漪：全螢幕層、中心＝頭像中心；clipPath＝min(96dp, 內切圓−偏移)≈79.5dp；scale 0.6→1.05（最大半徑 71.4dp）→ 弧線皆在可視邊緣內淡出
3. 極光：clipPath＝內切圓（116.5dp），底部光暈不滲出圓屏外緣
4. 全元素在內切圓內（雙環半寬 34dp@y79.5、名字 ≤81.5dp 皆 < 可視半寬）
5. V8 autosize 修復（16/40/1sp、無 ellipsize）一併帶上

## T9 v3.1 驗證（ui-spec v3.1：頭像區下移＋漣漪＋極光）
1. 頭像區再下移：頂部 padding 40dp、光環 68dp、頭像 52dp（24sp）→ 光環頂距圓屏邊 ≥60px
2. 擴散漣漪：頭像中心向外 3 圈圓環（scale 0.6→1.3、alpha 0.35→0、2.4s 循環、相位錯開 800ms）——雷達脈衝
3. 極光光暈：底部 radial 綠光（中心 78% 屏高、色 alpha ≤0.10）3.5s 緩慢呼吸
4. 舊中央 radial 層與波浪移除；雙層光圈/發光/文字微光/進場沿用；未接/斷線靜態
5. overlay 同視覺；連拍兩張看漣漪相位差與極光呼吸

## T9 動態背景驗證（ui-spec v3：radial 慢呼吸＋綠波浪）
1. 來電態：背景 radial 綠光 alpha 0.05→0.15 慢速呼吸（3s/週期，與光圈 1.2s 相位錯開）
2. 綠波浪：自繪 WaveBackgroundView（Canvas＋ValueAnimator）2~3 條半透明波紋自下而上緩慢流動（~9s/週期，alpha ≤0.14）
3. 未接/斷線/end：波浪停止繪製、radial 靜止 alpha 0.08（靜態背景）
4. 連拍兩張可看到波浪相位差；截圖確認不遮擋名字/頭像（背景氛圍不喧賓奪主）

## T9 頭像快取驗證（ui-spec v3 擴充：首次傳輸延遲感→快取秒顯）
1. 第一通來電（無快取）：首字 → 傳輸到達（~0.8s）→ 真頭像替換＋寫入快取
2. 第二通同一來電者：進入來電即真頭像（零延遲）；logcat {"t":"av_cache","action":"hit",...}
3. 未接/斷線畫面：有快取 → 真照片、無快取 → 首字（原「未接維持首字」已改快取優先）
4. 快取上限：≤10 名字、每張 ≤12KB、LRU 淘汰；檔案於 files/avatars/<sha16>.jpg
5. 頭像晚到（已 end）→ 不顯示但寫入快取（下一通秒顯）；chunk 間距由手機端 8ms→4ms（手錶端無 pacing 常數）

## T9 來電 UI v3 驗證（ui-spec v3：修光環切割＋更多特效）
1. 圓屏完整顯示：72dp 光環＋56dp 頭像＋內容下移（頂部 padding 28dp）→ 466×466 圓屏無切割
2. 雙層光圈：外層光暈 0.15→0.55／內層實心環 0.55→0.15（反向相位，1200ms/週期）
3. 頭像外發光 0.2→0.6 同相呼吸；標題/名字文字微光 0.85→1.0（進場後 350ms 啟動）
4. 背景 radial 綠光 0.5→1.0（非常 subtle）；未接/斷線態全部靜止低 alpha
5. 首字→真照片：scale 0.9→1＋alpha 0.6→1 cross-fade 300ms（API 30 無 RenderEffect）
6. overlay 備援同視覺；截圖驗證：來電態連拍兩張看 alpha 差異、圓屏四角無切割

## T8 真實頭像傳輸驗證（protocol.md v2 頭像傳輸、ui-spec v2 顯示規則）
1. 真實 LINE 來電：先顯示首字頭像 → 頭像到達（≤3s）後圓形裁切替換（Activity 與 overlay 雙路徑）
2. logcat 序列（LineWatchWatch）：{"t":"av_start",...} → N×{"t":"av_chunk",...} → {"t":"av_end",...}
   → {"t":"av_show","displayed":true} → 手機端收到 {"t":"ack","type":"av_end"}
3. 失敗路徑：SHA 不符/缺塊/5s 逾時 → {"t":"av_fail","reason":"sha|missing|timeout"} → 手機重試一次
4. 未接/斷線畫面維持首字；頭像晚到（已 end）→ av_show displayed=false（仍 ack）
5. 下一通來電 → 清空快取回歸首字；CHAR_CMD 已含 WRITE_NO_RESPONSE（av_chunk 免回應）

## T7 來電介面美化驗證（ui-spec v2：roadmap V2-2/V2-3/V2-4-v1）
1. 深連結來電：上方 64dp 首字頭像（LINE 綠底＋白字首字）＋84dp 光圈 alpha 呼吸（約 1200ms/週期）
2. 進場：頭像＋名字 scale 0.85→1（240ms）、標題/副標淡入（300ms）；切換未接/斷線態同樣套用
3. 未接/斷線態：頭像底與光圈改警示色/灰、光圈靜止 alpha 0.35
4. overlay 備援（關屏來電）：相同視覺（頭像＋光圈呼吸＋進場）
5. 純 AOSP android.animation，無粒子；logcat 慣例不變
6. 名字首字：中文取第一字、英文取第一字母、emoji 正確（codePoint 處理）、空白 → 「?」

## T6 設定畫面（ui-spec V2-1）驗證
1. 手錶應用選單出現「LINE 來電提醒」→ 點入 SettingsActivity（黑底、綠 accent）
2. 藍牙狀態列：手機連線中顯示「藍牙：已連線」（綠）＋「手錶 <名稱>」；斷線顯示「藍牙：待機中」（灰）
3. 震動強度/節奏：選檔即寫入 prefs，logcat 出現 {"t":"settings",...}；下一個來電震動立即用新值
   （dumpsys vibrator 看波形；強度以振幅 100/150/200 對照）
4. 測試按鈕：測試來電→來電畫面＋震動；停止→立即停；測試未接→未接畫面 8s
5. 開機自啟說明一行於底部（灰 12sp）
6. adb 直開：adb -s %WT% shell am start -n com.linewatch.watch/.SettingsActivity

## 已知問題（不影響功能）
- ColorOS 通知中心會拒顯 FGS 常駐通知（HeyNotification can not post）→ 不影響 GattServer／震動／提醒功能，僅通知列無常駐卡片。
- 【已修】斷線後廣告未重啟（T8 聯測發現）：advertising 旗標由非同步回呼設定造成 stop/start 競態 →
  已改為 worker thread 序列化的三態狀態機（IDLE/STARTING/ADVERTISING）＋30s 自癒檢查（無連線卻未廣播 → 重啟）。
- 手錶 BAL 限制：背景（螢幕未亮）startActivity 被系統 Abort → 顯示由 overlay 備援接手（見測試指令 10）。

## logcat

    adb -s %WT% logcat -s LineWatchWatch:*
