package com.linewatch.watch

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView

/**
 * D16：Yomogi 手寫感日系字體（res/font/yomogi_regular.ttf，SIL OFL 1.1 授權，google/fonts）。
 * 所有通知文字（來電/未接/斷線的標題、名字、副標、首字頭像）統一使用。
 * getFont 為 API 26+ 原生 API（本錶 API 30），無 androidx 依賴（D6 純 AOSP）。
 */
object Fonts {
    @Volatile
    private var cached: Typeface? = null

    fun yomogi(context: Context): Typeface? {
        cached?.let { return it }
        return try {
            val tf = context.resources.getFont(R.font.yomogi_regular)
            cached = tf
            tf
        } catch (e: Exception) {
            null
        }
    }

    fun applyYomogi(context: Context, vararg views: TextView) {
        val tf = yomogi(context) ?: return
        for (v in views) v.typeface = tf
    }
}
