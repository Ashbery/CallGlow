# CallGlow — LINE 來電手錶持續提醒系統

中國版 ColorOS Watch（OPPO Watch X3 / OWW261）在收到 LINE 語音／視訊來電時，
「持續震動＋全螢幕顯示來電者」，直到來電結束（接聽／拒絕／超時／未接）。
手錶只負責明顯提醒、不接聽。全程不依賴 GMS／Google Play services。

## 硬體與環境

| 項目 | 值 |
|---|---|
| 手錶 | OPPO Watch X3（OWW261），ColorOS Watch，Android 11（SDK 30），32-bit（armeabi-v7a），無 GMS |
| 手機 | Android 11+ 任一手機（實測紅魔 11 Pro+ / Android 16；其他品牌需依各廠設定允許通知監聽＋後台運行） |
| LINE | jp.naver.line.android（來電通知由 NotificationListenerService 讀取） |

## 功能

- LINE 語音／視訊來電 → 手錶持續震動＋全螢幕來電畫面（頭像、漣漪、星空動效）
- 接聽／拒接／超時 → 立即停止；未接 → 顯示「LINE 未接來電：名字」，息屏即隱藏（看過一次不再重顯）、
  螢幕持續亮則 8s 自動關或右滑關閉
- 震動持續到接通／掛斷（含息屏期間——ColorOS 息屏會取消 haptic，已自動重掛）；來電畫面抬腕顯示一次後不再重複亮起
- 通知文字統一 Yomogi 手寫感日系字體（SIL OFL）；名字最大 28sp、超長名字橫向滾動（marquee）
- 銀河主題來電特效：極光星雲背景、光環隨震動節拍明滅＋自轉、螢幕邊緣呼吸光環、紫/青/洋紅漣漪
- 真實頭像：LINE 通知 largeIcon → 壓縮 → BLE 分塊傳輸 → 手錶以名字為鍵快取（LRU ≤10 人）
- 斷線提示畫面；看門狗（手機 90s／手錶 120s）；心跳 ping/pong 10s；斷線自動重連
- 手錶設定畫面：震動強度三檔（100/150/200）、節奏三檔、系統震動效果、來電預覽

## 架構

```
[手機 LINE] → NotificationListenerService（偵測來電/接聽/未接）
          → CallStateMachine（未接判定）
          → BLE GATT Central（Nordic UART Service）
              ⇅ GATT（CMD 寫入 / STATE 通知；MTU 517）
[手錶]    BLE Peripheral（FGS 常駐 + 開機自啟）
          → 狀態機 → 持續震動 + IncomingCallActivity（/ overlay 備援）
```

詳細協議見 [docs/protocol.md](docs/protocol.md)、架構見 [docs/architecture.md](docs/architecture.md)、
決策紀錄見 [docs/decisions.md](docs/decisions.md)。

## 目錄

| 路徑 | 內容 |
|---|---|
| docs/ | 協議、架構、UI 規格、決策紀錄、測試計畫 |
| phone-app/ | 手機端 Android 專案（com.linewatch.phone，Java，76 個 JVM 單元測試） |
| watch-app/ | 手錶端 Android 專案（com.linewatch.watch，Kotlin，純 AOSP 無 androidx.wear） |

## 建置

需求：JDK 17 或 21（**勿用 JDK 26**——Gradle 8.6/8.7 會報 `IllegalArgumentException: 26`）、Android SDK 34。

每個 app 目錄都含 Gradle Wrapper：

```bat
cd phone-app
gradlew.bat assembleDebug testDebugUnitTest
cd ..\watch-app
gradlew.bat assembleDebug
```

APK 輸出：`phone-app\app\build\outputs\apk\debug\app-debug.apk`、
`watch-app\app\build\outputs\apk\debug\app-debug.apk`。
（首次執行需先建立 `local.properties` 指向本機 SDK，例如
`sdk.dir=C\:\\Users\\<你的使用者>\\AppData\\Local\\Android\\Sdk`；
此檔已加入 .gitignore。）

## 安裝（adb）

```bat
set PH=<手機序列號>
set WT=<手錶序列號>

:: 手機端
adb -s %PH% install -r phone-app\app\build\outputs\apk\debug\app-debug.apk
:: 啟用通知監聽：手機「設定 → 通知與狀態列 → 通知監聽權限」勾選 LineWatch，
:: 或 adb shell settings put secure enabled_notification_listeners com.linewatch.phone/.LineCallListenerService

:: 手錶端
adb -s %WT% install -r watch-app\app\build\outputs\apk\debug\app-debug.apk
adb -s %WT% shell appops set com.linewatch.watch SYSTEM_ALERT_WINDOW allow
adb -s %WT% shell appops set com.linewatch.watch WRITE_SETTINGS allow
```

### ⚠️ 手錶必做：ColorOS 凍結防護白名單（漏掉會斷線！）

實測根因：螢幕關閉後 ColorOS Watch 的 BmPowerManager 會凍結第三方 App（BLE 斷線、重連要 4–16 分鐘），
且 WearFrw 封鎖第三方 AlarmManager 喚醒。修法＝把 App 加入系統 AOD 白名單：

```bat
:: aod_support_apps 為受保護鍵，第三方 App 無法自寫 → 必須 adb 一次性寫入（跨重啟持久）
adb -s %WT% shell settings get global aod_support_apps
:: 把上面輸出整串加上 ,com.linewatch.watch 後執行：
adb -s %WT% shell settings put global aod_support_apps "<輸出值>,com.linewatch.watch"
```

其餘兩鍵（aod_switch_apps）由 App 開機時自癒寫回；詳見 watch-app/README.md 與 decisions.md D13。

## 換手錶／換手機（可攜性）

**換同型號手錶（另一支 OPPO Watch X3 / OWW261）**：直接裝同一支 watch APK（armeabi-v7a 相容），
再執行上面的 appops＋白名單命令即可。手機端掃描過濾器只認 NUS 服務 UUID、不認 MAC／序號，
另一支錶廣播相同 UUID 就能連上。

**換不同手機**：裝同一支 phone APK（minSdk 30），啟用通知監聽（見上）、
授予藍牙權限（Android 12+ 需 BLUETOOTH_SCAN／CONNECT 執行期授權：
`adb shell pm grant com.linewatch.phone android.permission.BLUETOOTH_SCAN` 與 `BLUETOOTH_CONNECT`，
或於設定中手動允許）、並依手機品牌允許後台運行／電池白名單（各廠路徑不同）。
手機端可選設定「目標裝置名稱」過濾，預設空＝自動連第一個 NUS 裝置。

**注意**：通知監聽權限在部分品牌（MIUI／ColorOS 手機等）需手動於設定中開啟；
LINE 需保持「來電通知」開啟；LINE 於前景時不貼未接通知（已知限制，未接畫面靠 5s 判定窗補送）。

## 疑難排解

- **紅魔手機**：安裝前需 `adb shell settings put system adb_install_enabled 1`；看 log 需
  `adb shell setprop log.tag.LineWatchPhone V`（重開機後重設）。
- **手錶斷線頻繁**：確認白名單已加（見上）；檢查 `adb shell settings get global aod_support_apps`。
- **來電畫面被擋**：ColorOS 限制背景啟動 Activity → 已內建 overlay 備援（需 SYSTEM_ALERT_WINDOW 授權）。
- **JDK 26 建置失敗**：改用 JDK 17/21 並設定 JAVA_HOME。

## 授權

[MIT](LICENSE)。手錶 App 圖示（ic_disconnect_kawaii / launcher）為專案原創素材，隨本倉庫以 MIT 釋出。

## 歷程

T1 環境/藍牙探測 → T2 手機端 → T3 手錶端 → T4 整合 → T5 穩定化 → T6 設定畫面 →
T7 視覺美化 → T8 真實頭像傳輸 → T9 震動模式/手勢/凍結自癒。詳見 docs/roadmap.md 與 docs/decisions.md。
