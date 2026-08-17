package com.linewatch.watch

import android.util.Log
import org.json.JSONObject

/**
 * BLE 通訊協定（docs/protocol.md v1.0 手錶端實作）。
 * 手機→手錶：start / end / ping；手錶→手機：ack / pong。
 */
object Protocol {

    // logcat 慣例（docs/test-plan.md v2.0 §0）：
    // tag 固定 LineWatchWatch；關鍵事件（start/end/ping/pong/連線/斷線/看門狗）輸出完整 JSON
    const val LOG_TAG = "LineWatchWatch"

    /** D18 正式版：關閉常態日誌（開發/除錯時改 true）。Log.w/Log.e 不受此開關控制。 */
    const val LOG_ENABLED = false

    /** 關鍵事件 log：單行完整 JSON。測試驗收全靠 logcat，勿改 tag。 */
    fun logEvent(message: String) {
        if (LOG_ENABLED) Log.i(LOG_TAG, message)
    }

    /** 常態資訊日誌（受 LOG_ENABLED 控制）。 */
    fun logI(message: String) {
        if (LOG_ENABLED) Log.i(LOG_TAG, message)
    }

    /** 常態除錯日誌（受 LOG_ENABLED 控制）。 */
    fun logD(message: String) {
        if (LOG_ENABLED) Log.d(LOG_TAG, message)
    }

    // GATT（Nordic UART Service）
    const val SERVICE_UUID = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
    const val CHAR_CMD_UUID = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
    const val CHAR_STATE_UUID = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
    const val CCCD_UUID = "00002902-0000-1000-8000-00805F9B34FB"

    // Service <-> Activity 內部廣播（setPackage 限定同包）
    const val ACTION_STATE = "com.linewatch.watch.action.STATE"
    const val EXTRA_STATE = "state"
    const val STATE_CALL = "call"
    const val STATE_MISSED = "missed"
    const val STATE_END = "end"
    const val STATE_DISCONNECTED = "disconnected"

    // Debug 深連結（Activity → Service）與 adb broadcast 模擬 end
    const val ACTION_DEBUG_START = "com.linewatch.watch.action.DEBUG_START"
    const val ACTION_DEBUG_END = "com.linewatch.watch.action.DEBUG_END"
    // P7 背景啟動（BAL）隔離測試（docs/probe-report.md P7；僅 debug 建置註冊）
    const val ACTION_TEST_BG_START = "com.linewatch.watch.action.TEST_BG_START"

    // 計時（與 docs/protocol.md / decisions.md 一致）
    const val CALL_TIMEOUT_MS = 120_000L   // 手錶端看門狗：120s
    const val MISSED_AUTO_FINISH_MS = 8_000L   // 未接/斷線：螢幕未熄時 8s 自動關（右滑可提前關）
    const val MAX_NAME_BYTES = 60

    // D15（2026-08-17 使用者裁決）：未接/斷線畫面不自動關閉，改右滑關閉；
    // 來電震動於息屏後重掛（ColorOS 息屏會取消第三方震動），直到接通/掛斷
    const val ACTION_HIDE_UI = "com.linewatch.watch.action.HIDE_UI"   // Service → Activity：隱藏來電畫面（不結束通話）
    const val VIBRATION_REARM_MS = 5_000L                             // 息屏期間震動重掛週期

    // 未接震動節奏（docs/decisions.md D4 固定，不隨設定變動）
    // 來電節奏三檔與震動強度改由 Prefs 即時讀取（ui-spec V2-1 SettingsActivity）
    val MISSED_PATTERN = longArrayOf(300, 200, 300)

    // Service → SettingsActivity 藍牙狀態廣播（setPackage 限定同包）
    const val ACTION_CONN_STATE = "com.linewatch.watch.action.CONN_STATE"
    const val EXTRA_CONNECTED = "connected"

    // Service → IncomingCallActivity：頭像已更新（重新渲染）
    const val ACTION_AVATAR = "com.linewatch.watch.action.AVATAR"

    // v3.19 ColorOS 凍結自癒：AlarmManager 每 60s 喚醒自檢
    const val ACTION_KEEPALIVE = "com.linewatch.watch.action.KEEPALIVE"
    const val KEEPALIVE_INTERVAL_MS = 60_000L

    // v2 頭像傳輸（protocol.md v2 章節）
    const val AVATAR_TIMEOUT_MS = 5_000L   // av_start 後整場逾時

    data class Command(
        val t: String,
        val name: String?,
        val kind: String?,
        val missed: Boolean,
        val seq: Int,
        // v2 頭像傳輸欄位
        val ts: Long,
        val total: Int,
        val bytes: Int,
        val index: Int,
        val data: String?,
        val sha: String?,
    )

    /** 解析單一 JSON 指令；無效 JSON → null（忽略，向前相容）。 */
    fun parseCommand(bytes: ByteArray): Command? {
        return try {
            val obj = JSONObject(String(bytes, Charsets.UTF_8))
            Command(
                t = obj.optString("t"),
                name = obj.optString("name").takeIf { it.isNotBlank() },
                kind = obj.optString("kind").takeIf { it.isNotBlank() },
                missed = obj.optBoolean("missed", false),
                seq = obj.optInt("seq", 0),
                ts = obj.optLong("ts", 0L),
                total = obj.optInt("total", 0),
                bytes = obj.optInt("bytes", 0),
                index = obj.optInt("i", -1),
                data = obj.optString("d").takeIf { it.isNotBlank() },
                sha = obj.optString("sha").takeIf { it.isNotBlank() },
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 名稱截斷至 60 bytes（UTF-8），配合 protocol.md 上限。 */
    fun truncateName(name: String): String {
        var s = name
        while (s.toByteArray(Charsets.UTF_8).size > MAX_NAME_BYTES && s.isNotEmpty()) {
            s = s.dropLast(1)
        }
        return s
    }

    fun ack(type: String): String = JSONObject()
        .put("t", "ack")
        .put("type", type)
        .toString()

    fun pong(seq: Int): String = JSONObject()
        .put("t", "pong")
        .put("seq", seq)
        .toString()

    /** v2 頭像傳輸失敗通知（reason: sha / missing / timeout）。 */
    fun avFail(ts: Long, reason: String): String = JSONObject()
        .put("t", "av_fail")
        .put("ts", ts)
        .put("reason", reason)
        .toString()
}
