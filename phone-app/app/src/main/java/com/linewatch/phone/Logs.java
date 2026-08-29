package com.linewatch.phone;

import android.util.Log;

/**
 * D18 常態日誌（預設關閉）。
 * D19（v1.0.1）：改執行期開關——adb shell setprop log.tag.<tag> V 即啟用（免重裝）。
 * Log.w/Log.e 請直接用 Log，不受此開關控制。
 */
public final class Logs {

    private Logs() {}

    public static void i(String tag, String msg) {
        LogFile.write("I", tag, msg);   // v1.0.2：檔案日誌一律寫入（3 天保留）
        if (Log.isLoggable(tag, Log.DEBUG)) Log.i(tag, msg);
    }

    public static void d(String tag, String msg) {
        LogFile.write("D", tag, msg);   // v1.0.2：檔案日誌一律寫入（3 天保留）
        if (Log.isLoggable(tag, Log.DEBUG)) Log.d(tag, msg);
    }
}
