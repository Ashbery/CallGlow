package com.linewatch.phone;

import android.content.Context;
import android.content.SharedPreferences;

/** 設定儲存（總開關、手錶名稱關鍵字）。 */
public final class Prefs {

    private static final String NAME = "linewatch_prefs";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_TARGET_NAME = "target_name";

    private Prefs() {}

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context c) {
        return sp(c).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context c, boolean v) {
        sp(c).edit().putBoolean(KEY_ENABLED, v).apply();
    }

    /** 手錶名稱關鍵字（空＝接受任何 NUS 廣播裝置）。 */
    public static String getTargetName(Context c) {
        return sp(c).getString(KEY_TARGET_NAME, "");
    }
}
