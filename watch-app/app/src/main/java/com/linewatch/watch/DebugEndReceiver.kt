package com.linewatch.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * T3/T4 測試用：以 adb broadcast 模擬手機送 end（走與 BLE end 相同的狀態機路徑）。
 * 僅在 debug 變體註冊（watch-app/app/src/debug/AndroidManifest.xml），release 不包含。
 *   adb shell am broadcast -a com.linewatch.watch.action.DEBUG_END --ez missed false
 *   adb shell am broadcast -a com.linewatch.watch.action.DEBUG_END --ez missed true
 */
class DebugEndReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Protocol.ACTION_DEBUG_END) return
        val i = Intent(context, BlePeripheralService::class.java)
            .setAction(Protocol.ACTION_DEBUG_END)
            .putExtra("missed", intent.getBooleanExtra("missed", false))
        context.startForegroundService(i)
    }
}
