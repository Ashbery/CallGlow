package com.linewatch.watch

import android.content.Context
import android.content.SharedPreferences
import android.os.VibrationEffect

/**
 * v2 使用者設定（docs/ui-spec.md V2-1 手錶端 SettingsActivity）：
 * 震動強度三檔與震動節奏三檔，SharedPreferences 持久化，VibratorController 即時讀取。
 */
object Prefs {

    private const val NAME = "linewatch_prefs"
    private const val KEY_STRENGTH = "vib_strength"
    private const val KEY_PATTERN = "vib_pattern"
    private const val KEY_VIB_MODE = "vib_mode"
    private const val KEY_USE_SYSTEM_EFFECT = "use_system_effect"
    private const val KEY_PULSAR = "vib_pulsar"   // v1.0.3：Pulsar 震動模式（""＝自訂節奏）

    // 震動強度（振幅 0-255）：弱 100 / 中 150 預設 / 強 200
    const val STRENGTH_WEAK = 100
    const val STRENGTH_MEDIUM = 150
    const val STRENGTH_STRONG = 200

    // 震動節奏：急促 [300,150] / 適中 [600,400] 預設 / 長震 [1000,300]
    const val PATTERN_RAPID = "rapid"
    const val PATTERN_MEDIUM = "medium"
    const val PATTERN_LONG = "long"

    // v3.17 震動模式：""＝自訂節奏；其餘＝系統預定義效果（API 29+；ColorOS 完整效果清單無公開 API）
    const val MODE_CUSTOM = ""
    const val MODE_CLICK = "click"
    const val MODE_DOUBLE_CLICK = "double_click"
    const val MODE_TICK = "tick"
    const val MODE_HEAVY_CLICK = "heavy_click"

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun getStrength(context: Context): Int =
        prefs(context).getInt(KEY_STRENGTH, STRENGTH_MEDIUM)

    fun setStrength(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_STRENGTH, value).apply()
    }

    fun getPatternKey(context: Context): String =
        prefs(context).getString(KEY_PATTERN, PATTERN_MEDIUM) ?: PATTERN_MEDIUM

    fun setPatternKey(context: Context, value: String) {
        prefs(context).edit().putString(KEY_PATTERN, value).apply()
    }

    fun getVibMode(context: Context): String =
        prefs(context).getString(KEY_VIB_MODE, MODE_CUSTOM) ?: MODE_CUSTOM

    fun setVibMode(context: Context, value: String) {
        prefs(context).edit().putString(KEY_VIB_MODE, value).apply()
    }

    /** v1.0.3：Pulsar 震動模式（""＝自訂節奏；其餘＝PulsarPresets.ITEMS 的 key）。 */
    fun getPulsarPreset(context: Context): String =
        prefs(context).getString(KEY_PULSAR, "") ?: ""

    fun setPulsarPreset(context: Context, value: String) {
        prefs(context).edit().putString(KEY_PULSAR, value).apply()
    }

    /** v3.17：是否使用系統震動效果（false＝自訂節奏，預設）。 */
    fun getUseSystemEffect(context: Context): Boolean =
        prefs(context).getBoolean(KEY_USE_SYSTEM_EFFECT, false)

    fun setUseSystemEffect(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_USE_SYSTEM_EFFECT, value).apply()
    }

    /** 系統預定義效果對應（custom → -1）。 */
    fun predefinedEffectFor(mode: String): Int = when (mode) {
        MODE_CLICK -> VibrationEffect.EFFECT_CLICK
        MODE_DOUBLE_CLICK -> VibrationEffect.EFFECT_DOUBLE_CLICK
        MODE_TICK -> VibrationEffect.EFFECT_TICK
        MODE_HEAVY_CLICK -> VibrationEffect.EFFECT_HEAVY_CLICK
        else -> -1
    }

    /** 來電節奏波形（震動/停頓交替）。未接節奏依 docs/decisions.md D4 固定，不隨設定變動。 */
    fun callPatternFor(key: String): LongArray = when (key) {
        PATTERN_RAPID -> longArrayOf(300, 150)
        PATTERN_LONG -> longArrayOf(1000, 300)
        else -> longArrayOf(600, 400)
    }

    /** 依節奏產生振幅陣列（震動段 = 強度、停頓段 = 0）。 */
    fun amplitudeWave(pattern: LongArray, strength: Int): IntArray =
        IntArray(pattern.size) { i -> if (i % 2 == 0) strength else 0 }
}
