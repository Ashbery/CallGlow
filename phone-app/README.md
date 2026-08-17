# phone-app 手機端（com.linewatch.phone）

LINE 來電 → NotificationListenerService 監聽 → CallParser／CallStateMachine → BLE GATT（Nordic UART Service）通知手錶持續震動與顯示來電者。

規格來源（唯一權威，勿修改）：
- `docs/protocol.md`（訊息格式／UUID／心跳／看門狗）
- `docs/architecture.md`（元件架構）
- `docs/ui-spec.md`（手機端畫面）
- `docs/decisions.md`（定案紀錄）
- `docs/test-plan.md` §0（logcat tag 慣例：手機端統一 `LineWatchPhone`）

## 元件對照

| 檔案 | 元件 | 說明 |
|---|---|---|
| `LineCallListenerService.java` | 通知監聽 | 監聽 `jp.naver.line.android`；onNotificationPosted/Removed（3-arg 含 reason）→ CallParser → CallStateMachine → BLE 指令；90s 看門狗；接聽轉換（通話中）→ 立即 end(false)；removed 後 5s 未接判定窗 → t="missed" |
| `CallParser.java` | 純邏輯解析 | 來電／missed／通話中(isOngoing) 判定（title+subText+text）、名稱解析（title→subText→text 首行）、kind 判定；JVM 單元測試 |
| `CallStateMachine.java` | 狀態機 | IDLE/RINGING；removed→end(false)＋武裝 5s 未接判定窗；repost-missed→end(true)；窗內 missed→MISSED(t="missed")；ongoing→end(false)；90s watchdog→end(true) |
| `MissedVerdict.java` | 判定窗 | 未接判定窗（protocol v1.1：5 秒、一次有效、純邏輯） |
| `BleCentralService.java` | BLE Central FGS | type=connectedDevice；掃描過濾 NUS UUID；autoConnect；requestMtu(247)；寫 CHAR_CMD；心跳 10s；3 次無 pong 重連（5s/15s/30s）；重連後補送同步 |
| `Command.java` | 訊息建構 | UTF-8 JSON、≤200 bytes、name ≤60 bytes（`{"t":"start","name":"…","kind":"voice|video"}`、`{"t":"end","missed":…}`、`{"t":"missed","name":"…","kind":"…"}` v1.1、`{"t":"ping","seq":…}`） |
| `MainActivity.java` | 設定頁 | 狀態列、總開關、測試來電／測試未接／停止測試（直送 BLE）、通知存取跳轉、藍牙權限請求 |
| `StatusBus.java`／`Prefs.java` | 輔助 | 主執行緒狀態匯流排／SharedPreferences |

## 環境需求

- Android Studio（內建 JDK 17+ 即可；本機 JDK 21 也相容）
- Android SDK Platform 34（本機已裝，`local.properties` 已指向 `C:\Users\<使用者>\AppData\Local\Android\Sdk`）
- **本專案不含 `gradle-wrapper.jar`**（二進位無法由 agent 生成）；已寫好 `gradle/wrapper/gradle-wrapper.properties`（Gradle 8.7），由 Android Studio 開啟時自動補齊 wrapper。

## 建置步驟（主要路徑：Android Studio）

1. Android Studio → **File → Open** → 選 `phone-app/` 資料夾 → **Trust Project**。
2. 首次 Sync 自動下載 Gradle 8.7 與 AGP 8.5.2；若提示缺少 wrapper → 讓 AS 依 `gradle-wrapper.properties` 自動產生（AS 會建立 `gradlew`／`gradlew.bat`／`gradle-wrapper.jar`）。
3. 等右下角 Gradle Sync 完成、無紅色錯誤。
4. **Build → Build APK(s)** → 產出 `phone-app/app/build/outputs/apk/debug/app-debug.apk`。

## 命令列建置（選用）

wrapper 產生後（AS 開過一次即有）：
```
cd /d <專案根目錄>\phone-app
gradlew.bat assembleDebug
gradlew.bat testDebugUnitTest
```
- 若用本機 JDK 21 跑 gradlew：設 `JAVA_HOME=C:\Program Files\Java\jdk-21`（Gradle 8.7 支援 JDK 21）。
- 若本機另有 standalone Gradle 且 wrapper 尚未產生：`gradle wrapper --gradle-version 8.7`。

## 安裝與授權（cmd，test-plan §2.2）

> ⚠️ 紅魔 ROM 安裝守衛（T2 實測）：預設 `adb_install_enabled=0` 會讓 adb install 全部 SecurityException（session -1）。
> 先執行一次：`adb -s %PH% shell settings put system adb_install_enabled 1`（設定會保留）。

```
set PH=<手機 adb 序號>
adb -s %PH% shell settings put system adb_install_enabled 1
adb -s %PH% install -r phone-app\app\build\outputs\apk\debug\app-debug.apk
adb -s %PH% shell pm grant com.linewatch.phone android.permission.BLUETOOTH_SCAN
adb -s %PH% shell pm grant com.linewatch.phone android.permission.BLUETOOTH_CONNECT
adb -s %PH% shell pm grant com.linewatch.phone android.permission.POST_NOTIFICATIONS
adb -s %PH% shell cmd notification allow_listener com.linewatch.phone/.LineCallListenerService
adb -s %PH% shell settings get secure enabled_notification_listeners
```
最後一行應包含 `com.linewatch.phone`（T1 探測：OHealth 的 HeytapNotificationListenerService 亦在列，多 listener 並存互不影響）。
若 `allow_listener` 失敗：手機 設定 → 通知與狀態列 → 通知使用權 → 允許「LINE 來電提醒」。
（App 內亦可：點「授予通知存取權限」；開總開關時會自動彈藍牙／通知執行期權限。）

紅魔省電白名單（probe-report P12，T5 前置；T1 探測：zte.powersavemode 在 listeners，需加白名單）：
```
adb -s %PH% shell cmd deviceidle whitelist +com.linewatch.phone
adb -s %PH% shell dumpsys deviceidle whitelist | findstr linewatch
```
手動設定：設定 → 電池 → 省電策略 → 「LINE 來電提醒」設無限制；安全中心 → 自啟動管理 → 允許；通知管理 → 允許通知。

T1 環境確認（2026-08 探測）：LINE 26.11.0 已裝；手機系統層已與 OPPO Watch X3（<裝置尾碼>）配對 → BleCentralService 掃到 NUS 廣播直接 GATT 連線即可，無需重新配對。

## 單元測試

- Android Studio：右鍵 `phone-app/app/src/test/java/com/linewatch/phone/CallParserTest` → Run；或 Run `CallStateMachineTest`。
- CLI：`gradlew.bat testDebugUnitTest`。
- 驗收標準（test-plan §2.3）：全部用例綠。

## T2 冒煙驗收（test-plan §2.4）

> ⚠️ 紅魔 ROM 預設全域 `log.tag=S` 會靜默所有 app log（T2 實測）→ 收 log 前必須先執行：
> `adb -s %PH% shell setprop log.tag.LineWatchPhone V`（**重開機失效，每次測試前要重設**）。

```
set PH=<手機 adb 序號>
adb -s %PH% shell setprop log.tag.LineWatchPhone V
adb -s %PH% logcat -s LineWatchPhone:*
```
預期事件序列：
1. 開 app → 狀態列「藍牙：已斷線」；開總開關 → `scan started (NUS UUID filter)`、狀態列「藍牙：掃描中」。
2. 無手錶時：每 30s 左右 `reconnect scheduled in 5000/15000/30000 ms` 順序 backoff。
3. 「測試來電」→ log 出現 `{"t":"start","name":"測試來電","kind":"voice"}`（未連線時顯示 not ready, queued）。
4. 「停止測試」→ `{"t":"end","missed":false}`；「測試未接」→ `{"t":"end","missed":true}`。
5. 有手錶（T3 完成後）：`connected` → `mtu = …`（實測 517）→ `write ok: …` → 連線就緒 10s 後起每 10s `pong received`（首筆 ping 延後一週期，避免連線初期 GATT 競態）；測試來電後 90s 不按停止 → `test watchdog fired -> {"t":"end","missed":true}`。
6. T4 真實來電（protocol v1.1）：接聽 → log `ongoing detected -> {"t":"end","missed":false}`（LINE 通話中頻道 VoIP.02.Ongoing）；未接 → removed 後 5s 內 LINE 貼未接通知 → `missed verdict window hit -> {"t":"missed","name":"…","kind":"…"}`。所有 LINE 通知有一行 `LINE notify: key=… channel=… category=… title=… sub=… text=…` 證據 log；removed 有 `reason=` log。
7. D 場景三態判定窗（C-lite 裁示）：removed → 立即 end(false)＋武裝 5s 窗；窗內未接通知 → `missed verdict window hit -> {"t":"missed",…}`；Ongoing 到達 → `ongoing while idle (answered, verdict settled): <N>ms since removed`；窗到期 → `verdict expired decision=missed|answered|none reason=<N> delay=<N>ms`（decision=missed 時補送 t="missed"，name/kind 用響鈴值）。已知代價：拒接（reason=8 且無 Ongoing）→ 5s 後顯示未接畫面（產品已接受）。

## v1 已知限制（T5 再處理）

- BLE 掃描為連續低延遲（未做省電週期）。
- 來電關鍵字表集中於 `CallParser`（LINE 改版只需調此處，D8）。
- 測試按鈕指令直送 BLE 不經狀態機；其 90s 看門狗位於 MainActivity。
- 未做開機自啟（test-plan §5.7 為選項，超出 v1 架構）。
- 名稱解析若 LINE 通知格式變化 → fallback「未知聯絡人」，並於 T5 §5.6 擴充樣本回歸。
