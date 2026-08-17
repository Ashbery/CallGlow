# T9 視覺驗收清單（來電 UI v3：修光環切割＋更多特效）

> 維護者：integration-tester。驗收方式：captain 以 adb 截圖存進 repo（t9-screens/），
> integration-tester 以視覺工具逐張判讀並回填結果。手錶 %WT%=%WT%。

## 0. 截圖指令（captain 執行）
```
set WT=%WT%
:: 1) 觸發來電畫面（深連結，可重複）
adb -s %WT% shell am start -n com.linewatch.watch/.IncomingCallActivity --es name 測試聯絡人 --ez missed false
:: 2) 截圖（每次換檔名）
adb -s %WT% shell screencap -p /sdcard/t9_call.png
adb -s %WT% pull /sdcard/t9_call.png t9-screens/t9_call.png
```
同理：未接（--ez missed true）、斷線提示、SettingsActivity（am start -n com.linewatch.watch/.SettingsActivity）。
真頭像：由手機測試按鈕觸發（走 av_start→chunks→av_show），截圖 t9_avatar.png。

## 1. 驗收項與判讀準則
| # | 項目 | 判讀準則 |
|---|---|---|
| V1 | 光環頂部切割修復 | 來電畫面光環完整顯示於圓形安全區內，頂部無被螢幕邊緣裁切 |
| V2 | 特效（T9 新增） | 特效可見且不遮擋名字/頭像/狀態文字；無過度閃爍（截圖多張對比） |
| V3 | 圓形安全區 | 內容限中央 70% 寬（233dp 屏 → ≈163dp），無貼邊元素 |
| V4 | 顏色 tokens | 背景 #000000、LINE 綠 #06C755、主文字 #FFFFFF、次要 #B0B0B0、警示 #FF8A65 與 ui-spec 一致 |
| V5 | 真頭像顯示 | av_show displayed:true 後，圓形裁剪真照片位於指定位置、無變形 |
| V6 | 首字頭像 fallback | 無頭像時顯示首字圓形（字體置中） |
| V7 | 未接畫面 | 標題警示色＋副標 12sp 灰＋8s 自動關（截圖可見） |
| V8 | 名字 40sp autosize | 長名字自動縮至 70% 寬不溢出 |
| V9 | SettingsActivity | 三檔震動選項可見可點（另截圖） |

## 2. 回傳與判讀
captain 將截圖存 t9-screens/ 並貼回檔名清單 → integration-tester 逐張視覺判讀 → 回填本文件結果欄 → 不合規項回報 watch-engineer 修正後重截。

## 3. 結果（2026-08-17 第一輪判讀）
| # | 結果 | 判讀依據 |
|---|---|---|
| V1 光環切割 | ✅ P | t9_real_avatar_call1/2：光環完整顯示於圓形安全區內，頂部無裁切 |
| V2 特效不遮擋 | ✅ P（靜態） | 兩張來電圖均見綠波浪於頭像後層、名字/標題清晰無遮擋；動態連拍驗證待補（見下） |
| V3 70% 安全區 | ✅ P | 內容全部居中、無貼邊元素 |
| V4 顏色 tokens | ✅ P | 黑底、LINE 綠標題、白名字、未接警示色與 ui-spec 一致 |
| V5 真頭像 | ✅ P | call1/call2/missed 三張皆圓形真照片、無變形；cache hit 第 2 通秒顯 |
| V6 首字 fallback | ⏳ 缺圖 | 待重截 |
| V7 未接畫面 | ✅ P（帶註記） | t9_missed_avatar：警示色「LINE 未接來電」＋真頭像＋名字正確；畫面有淡淡「充電完成/100%」系統浮層（拍攝時仍在充電座），不影響判定 |
| V8 長名字 autosize | ⏳ 錯檔 | t9_long.png 內容竟是 GitHub Actions 網頁截圖（錯檔），需重截 |
| V9 SettingsActivity | ⏳ 覆蓋 | t9_settings.png 為充電畫面，需離座重截 |

不可用檔案：t9_call.png／t9_call2.png／t9_missed.png（充電畫面覆蓋）、t9_long.png（錯檔：GitHub Actions 截圖）。
⚠️ 更正 captain 先前「t9_call vs t9_call2 byte 差異＝動畫運作」證據：該兩張皆為充電畫面，byte 差異不構成動畫證據 → 動態驗證改為連拍 2~3 張新來電畫面（間隔 1s）。

## 4. 第二輪補判（2026-08-17）
- V2 動態 ✅：t9_burst2/burst3 兩幀同場景隔 1s，byte 46,040/44,996 相異、綠波浪位置微異 → 動畫運作。t9_burst1 為錯檔（桌面 ModLens 面板截圖），不影響兩幀證據。
- V6 首字 fallback ✅：t9_initial_fallback.png 綠圓底首字「未」置中＋「LINE 來電」＋「未知聯絡人」＋波浪 ✅。
- V9 Settings ✅：t9_settings2.png 三檔強度（弱 100／中 150／強 200）＋三檔節奏（急促／適中／長震）＋測試來電按鈕 ✅。
- V8 長名字 ⏳：t9_long2.png 又是錯檔（桌面聊天 UI 截圖），需第三次重截。

## 5. V8 判讀（modlens 橋接確認）— ❌ F 項
t9_long3.png 確認為手錶畫面（先前「錯檔」為 read_image 橋接快取錯亂，檔案本身無誤——captain 澄清；**此後視覺判讀一律改用 modlens 橋接**）。
F 項證據（OCR）：12 字長名「王小明王小明王小明王小明」顯示為「王小明王小明王小...」——省略號截斷，autosize 未生效。
根因（integration-tester 讀 layout 確認）：activity_incoming_call.xml 的 name TextView 為 `layout_width="wrap_content"`＋`autoSizeTextType="uniform"` 但**無寬度約束**（無 maxWidth／非受限寬度）→ autosize 沒有可用空間可縮、恆持 40sp；maxLines=1 預設行為以「…」截斷。
修復方向（watch-engineer 進行中）：uniform autosize 14~40sp、移除 ellipsize（`android:ellipsize="none"`）、以螢幕寬 70% 約束（maxWidth 或程式設 widthPixels*0.7）。
修復重測後重截 t9_long4.png 補判。

## 6. V8 最終補判（modlens 驗證 t9_long5.png）— ✅ 通過
12 字名字「王小明王小明王小明王小明」單行完整顯示、無省略號、無裁切、寬度約 60~70% 在安全區內、無系統通知卡片遮擋 ✅。另確認：斷線 kawaii 圖示（灰圓＋斷訊弧線＋×_×＋小嘴，無頭像/首字）✅；漣漪收斂（多圈漣漪完全在圓形可視區內、無越界弧線）✅。
小觀察（非 F，不需動作）：長名字縮小後視覺上比標題略淡（autosize 縮字與反鋸齒所致，正常）。

## 7. 驗收總結（✅ 全項閉環 2026-08-17）
V1 光環無切割 ✅｜V2 特效不遮擋＋動態運作 ✅｜V3 70% 安全區 ✅｜V4 顏色 tokens ✅｜V5 真頭像（含 cache）✅｜V6 首字 fallback ✅｜V7 未接畫面 ✅｜V8 長名字 autosize ✅（F→修復→根治驗證）｜V9 Settings ✅。
附帶確認：斷線 kawaii 圖示 ✅、漣漪收斂 ✅。
**T9 視覺驗收：9/9 全過，無剩餘 F 項。**
