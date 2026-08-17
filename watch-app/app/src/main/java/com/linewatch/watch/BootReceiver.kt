package com.linewatch.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * 開機自動：
 * 1. v3.20 自癒 ColorOS 白名單（BmPowerManager 會凍結第三方 App → BLE 斷線；
 *    加入 aod_* 清單後實測螢幕關閉 8+ 分鐘 ping/pong 不中斷）
 * 2. 啟動 BLE 前台服務（常駐 Peripheral）。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Protocol.logEvent("{\"t\":\"boot\",\"action\":\"start_service\"}")
        ColorOsWhitelist.ensure(context)
        val i = Intent(context, BlePeripheralService::class.java)
        context.startForegroundService(i)
    }
}

/**
 * ColorOS Watch（WearFrw / BmPowerManager）後台凍結對策。
 *
 * 實證（2026-08-17）：
 * - 未加白名單：連線後 ~2 分鐘 Watch App 被凍結（log 靜默），重連要 4–16 分鐘。
 * - 加入白名單後：螢幕關閉 8+ 分鐘 ping/pong 持續；手機藍牙開關後 10 秒重連。
 * - 系統開機時會重建 aod_switch_all_apps（第三方 App 被移除）；實測凍結防護
 *   只需 aod_support_apps + aod_switch_apps 即可（aod_switch_all_apps 非必要）。
 *
 * 權限策略（實測）：
 * - aod_switch_apps：本 App 可寫（WRITE_SETTINGS appop，adb 授權）。
 * - aod_support_apps / aod_switch_all_apps：受保護鍵，第三方 App 寫入必被拒，
 *   由安裝腳本 adb settings put 一次性寫入（跨重啟持久）。
 *
 * adb 一次性安裝命令：
 *   appops set com.linewatch.watch WRITE_SETTINGS allow
 *   settings put global aod_support_apps "$(settings get global aod_support_apps),com.linewatch.watch"
 */
object ColorOsWhitelist {
    private const val KEY_SUPPORT = "aod_support_apps"
    private const val KEY_SWITCH = "aod_switch_apps"
    private const val KEY_ALL = "aod_switch_all_apps"

    fun ensure(context: Context) {
        val cr = context.contentResolver
        for (key in listOf(KEY_SUPPORT, KEY_SWITCH, KEY_ALL)) {
            try {
                val cur = Settings.Global.getString(cr, key) ?: ""
                if (!cur.split(",").contains(context.packageName)) {
                    val nv = if (cur.isEmpty()) context.packageName else cur + "," + context.packageName
                    Settings.Global.putString(cr, key, nv)
                    Protocol.logI("whitelist_ensure: 已加入 " + key)
                    Protocol.logEvent("{\"t\":\"whitelist_ensure\",\"key\":\"" + key + "\",\"ok\":true}")
                }
            } catch (e: Exception) {
                // 受保護鍵（support/all）第三方 App 寫入必被拒 → 記錄後繼續下一個鍵
                Log.w(Protocol.LOG_TAG, "whitelist_ensure " + key + " 失敗: " + e.message)
                Protocol.logEvent(
                    "{\"t\":\"whitelist_ensure\",\"key\":\"" + key + "\",\"ok\":false,\"err\":\"" + e.message + "\"}"
                )
            }
        }
    }
}
