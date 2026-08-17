# T5 穩定性執行清單（2026-08-17，captain 直接執行 adb）

> 維護者：integration-tester。驗收準則 docs/test-plan.md §5（含 §5.0 T4 遺留）。
> 序號：%PH%=%PH%、%WT%=%WT%。logcat tag：LineWatchPhone／LineWatchWatch。

## 0. 前置（一次性）
```
set PH=%PH%
set WT=%WT%
adb -s %PH% shell setprop log.tag.LineWatchPhone V
adb -s %PH% shell settings get global bluetooth_on
adb -s %WT% shell settings get global bluetooth_on
adb -s %WT% shell appops get com.linewatch.watch SYSTEM_ALERT_WINDOW
adb -s %PH% shell dumpsys battery | findstr /i level
adb -s %WT% shell dumpsys battery | findstr /i level
```
記錄兩台起始電量（對照 T1 P9 基線）。

## 1. 全程 logcat（兩個 cmd 視窗，T5 全程開著）
```
adb -s %PH% logcat -s LineWatchPhone:* AndroidRuntime:E -v time > t5_phone.log
adb -s %WT% logcat -s LineWatchWatch:* AndroidRuntime:E ActivityTaskManager:I -v time > t5_watch.log
```

## 2. §5.0 T4 遺留：C-lite 真實三情境（併入 2 小時行程的前 3 通）
| 情境 | 操作 | 判讀通過 |
|---|---|---|
| 接聽 | 測試機接聽 | 手錶不顯示未接畫面；log：removed→end(false)→Ongoing→「verdict window expired -> no action (answered...)」 |
| 拒接 | 測試機拒接 | 手錶 5s 後顯示未接畫面 8s（已知可接受）；log：expired→default missed |
| 對方掛斷 | 對方掛斷、測試機不接 | 手錶 5s 後顯示未接畫面 8s ✅；log：expired→default missed |
另：手錶離充電座，觀察顯示路徑（亮屏 Activity／關屏 turnScreenOn；overlay 不應觸發）。

## 3. §5.1 連續運行 2 小時
- 兩 app 常駐；真實 LINE 來電每 20 分鐘 1 次（共 6 次，前 3 次即 §5.0 三情境，後 3 次隨機場景）。
- 中間用測試按鈕補測（測試來電/停止/測試未接）至少 2 輪。
- 驗收：全程無 crash/ANR（log 掃 FATAL、ANR in）；每次來電提醒成功；心跳 ping/pong 持續規律（每 10s）。

## 4. §5.2 斷線重連（2 小時行程後段）
- 手錶關藍牙 40s（>3 次 pong 超時）→ 重開 → 手機 backoff 重連；循環 3 次。
- 手機飛航模式 60s → 恢復 → 重連。
- 判讀：phone.log 重連序列（backoff 5s/15s/30s）；重連後若已 IDLE 且未送 end → 補送 end(missed=true)（log 佐證）；watch.log connect/disconnect。
```
adb -s %WT% shell svc bluetooth disable
:: 40s 後
adb -s %WT% shell svc bluetooth enable
```

## 5. §5.3 看門狗複測
- 手機 90s：按「測試來電」後不按停止 → 90s±5s 自動 end(missed=true) → 手錶未接畫面。
- 手錶 120s：深連結 `am start ... --es name 測試 --ez missed false` 後不動 → 120s±5s 自動轉未接畫面 8s。

## 6. §5.4/§5.5 耗電與記憶體（2 小時前後各取一次）
```
adb -s %PH% shell dumpsys battery | findstr /i level
adb -s %WT% shell dumpsys battery | findstr /i level
adb -s %PH% shell dumpsys meminfo com.linewatch.phone | findstr /i "TOTAL"
adb -s %WT% shell dumpsys meminfo com.linewatch.watch | findstr /i "TOTAL"
```
判讀：手錶 2h 耗電 ≤30%（>30% 回報 captain）；記憶體成長 <20MB。

## 7. §5.7 重開機恢復（2 小時行程結束後）
- 兩台重開機 → 手錶 BootReceiver 自動啟 FGS（30s 內 `dumpsys activity services com.linewatch.watch` 見 isForeground=true）→ 手機開 app → 30s 內重連。
- ColorOS 攔截自啟動時：依 watch-app README 白名單操作後複測。

## 8. §5.6 通知格式 fallback
- CallParser 單元測試擴充多組 LINE 通知樣本（phone-engineer 配合），Android Studio 全綠即可；無需實機。

## 9. 回傳與判讀
每節完成後貼回：時間點、雙端 log 對應段落、觀察描述。integration-tester 逐項判讀回填 T5 報告（docs/t5-report.md）。
