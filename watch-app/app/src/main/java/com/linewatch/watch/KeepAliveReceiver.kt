package com.linewatch.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * v3.19 ColorOS 凍結自癒：AlarmManager 每 60s 喚醒 → 轉交 BlePeripheralService 自檢
 * （進程被凍結/殺死都能被 alarm 喚起；manifest exported=false）。
 */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Protocol.ACTION_KEEPALIVE) return
        val i = Intent(context, BlePeripheralService::class.java)
            .setAction(Protocol.ACTION_KEEPALIVE)
        context.startForegroundService(i)
    }
}
