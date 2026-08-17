package com.linewatch.watch

import android.os.Handler
import android.os.Looper

/**
 * 120s 看門狗（docs/decisions.md D3 手錶端）：
 * 收到 start 後未收到 end → 自動停止（視為 missed）。
 * 重複 arm 會先取消上一次計時（重新計時）。
 */
class Watchdog(
    private val timeoutMs: Long,
    private val onTimeout: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var armed = false

    private val runnable = Runnable {
        armed = false
        onTimeout()
    }

    fun arm() {
        cancel()
        handler.postDelayed(runnable, timeoutMs)
        armed = true
    }

    fun cancel() {
        handler.removeCallbacks(runnable)
        armed = false
    }

    fun isArmed(): Boolean = armed
}
