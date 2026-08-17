package com.linewatch.phone;

import android.util.Log;

/** D18 常態日誌（受 Constants.LOG_ENABLED 控制；Log.w/Log.e 請直接用 Log）。 */
public final class Logs {

    private Logs() {}

    public static void i(String tag, String msg) {
        if (Constants.LOG_ENABLED) Log.i(tag, msg);
    }

    public static void d(String tag, String msg) {
        if (Constants.LOG_ENABLED) Log.d(tag, msg);
    }
}
