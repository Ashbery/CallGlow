package com.linewatch.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * P7 背景 Activity 啟動（BAL）隔離測試（docs/probe-report.md P7；僅 debug 建置）。
 * 觸發：adb shell am broadcast -a com.linewatch.watch.action.TEST_BG_START --es name 背景測試
 *
 * receiver 於背景呼叫 startActivity：
 * - 畫面出現 → 此手錶背景啟動豁免有效
 * - logcat（LineWatchWatch）出現 result=blocked_or_timeout 或系統 blocked 訊息 → BAL 受限
 *   → 依 probe-report.md P7 判讀，受限才啟用 overlay 備援分支（先回報 captain）
 */
class BgStartTestReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Protocol.ACTION_TEST_BG_START) return
        val name = intent.getStringExtra("name") ?: "背景測試"
        val start = Intent(context, IncomingCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(IncomingCallActivity.EXTRA_NAME, name)
            .putExtra(IncomingCallActivity.EXTRA_MISSED, false)
            .putExtra(IncomingCallActivity.EXTRA_FROM_SERVICE, true) // 不震動、不轉交 service，純顯示測試
        try {
            context.startActivity(start)
            Protocol.logEvent("{\"t\":\"bg_start_test\",\"name\":\"$name\",\"result\":\"start_attempted\"}")
        } catch (e: Exception) {
            Protocol.logEvent("{\"t\":\"bg_start_test\",\"name\":\"$name\",\"result\":\"blocked\",\"error\":\"${e.message}\"}")
            return
        }
        // 背景啟動可能被「靜默攔截」（不拋例外）→ 2s 後以 Activity 可見性判定實際結果
        Handler(Looper.getMainLooper()).postDelayed({
            val visible = IncomingCallActivity.isVisible
            Protocol.logEvent(
                "{\"t\":\"bg_start_test\",\"name\":\"$name\",\"result\":\"" +
                    if (visible) "ok_visible" else "blocked_or_timeout" + "\"}"
            )
        }, 2000L)
    }
}
