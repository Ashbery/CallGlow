package com.linewatch.watch

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * 震動控制（docs/decisions.md D4 + ui-spec v3.16）：
 * - 來電：自訂節奏三檔（Prefs 即時讀取）無限循環；或系統預定義效果（CLICK/DOUBLE_CLICK/TICK/HEAVY_CLICK）
 *   以 700ms 週期重發循環（CALLING 期間）；end → 立即 cancel()＋停止重發
 * - 未接：[300,200,300] 單次不循環（D4 固定）；強度同 Prefs
 * - 震動強度：弱 100 / 中 150 預設 / 強 200（振幅），Prefs 即時讀取
 * - 重入保護：同一時間只有一組震動；震動啟動/停止成對
 */
class VibratorController(private val context: Context) {

    private enum class Mode { IDLE, CALLING, MISSED }

    companion object {
        /** 跨實例 CALLING 旗標：SettingsActivity 預覽震動在來電中跳過。 */
        @Volatile
        private var anyCalling = false

        fun isAnyCalling(): Boolean = anyCalling
    }

    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    private val lock = Any()
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatRunnable: Runnable? = null

    @Volatile
    private var mode = Mode.IDLE

    /** 來電震動。已在循環中 → 直接忽略（重入保護），回傳 false。 */
    fun startCall(): Boolean {
        synchronized(lock) {
            if (mode == Mode.CALLING) return false
            if (!vibrator.hasVibrator()) {
                mode = Mode.CALLING
                return true
            }
            val useSystem = Prefs.getUseSystemEffect(context)
            val vibMode = Prefs.getVibMode(context)
            if (!useSystem || vibMode.isEmpty()) {
                val strength = Prefs.getStrength(context)
                val pattern = Prefs.callPatternFor(Prefs.getPatternKey(context))
                vibrator.vibrate(
                    VibrationEffect.createWaveform(pattern, Prefs.amplitudeWave(pattern, strength), 0)
                )
            } else {
                // v3.16：系統預定義效果為單次 → 700ms 週期重發循環
                val effect = Prefs.predefinedEffectFor(vibMode)
                if (effect >= 0) {
                    repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
                    val runnable = object : Runnable {
                        override fun run() {
                            if (mode == Mode.CALLING && vibrator.hasVibrator()) {
                                try {
                                    vibrator.vibrate(VibrationEffect.createPredefined(effect))
                                } catch (e: Exception) {
                                    // 裝置不支援該效果 → 靜默降級
                                }
                                repeatHandler.postDelayed(this, 700L)
                            }
                        }
                    }
                    repeatRunnable = runnable
                    repeatHandler.post(runnable)
                }
            }
            mode = Mode.CALLING
            anyCalling = true
            return true
        }
    }

    /** 未接震動（單次）。若正在來電震動 → 先 cancel 再播未接。 */
    fun startMissed() {
        synchronized(lock) {
            if (mode == Mode.CALLING) {
                vibrator.cancel()
                stopRepeatLoop()
                anyCalling = false
            }
            if (vibrator.hasVibrator()) {
                val strength = Prefs.getStrength(context)
                vibrator.vibrate(
                    VibrationEffect.createWaveform(
                        Protocol.MISSED_PATTERN,
                        Prefs.amplitudeWave(Protocol.MISSED_PATTERN, strength),
                        -1
                    )
                )
            }
            mode = Mode.MISSED
        }
    }

    /** 停止所有震動。 */
    fun stop() {
        synchronized(lock) {
            if (mode != Mode.IDLE) vibrator.cancel()
            stopRepeatLoop()
            mode = Mode.IDLE
            anyCalling = false
        }
    }

    /** 是否正在來電循環震動（供斷線時判斷走 watchdog 或斷線提示）。 */
    fun isCalling(): Boolean = mode == Mode.CALLING

    /**
     * D15：重掛來電震動（ColorOS 息屏會取消第三方 App 的 haptic → 服務偵測息屏/亮屏後重掛）。
     * 自訂節奏：重發同一波形（循環重來）；系統效果：700ms 重發循環本就會自動續發 → 不動作。
     */
    fun rearmCall() {
        synchronized(lock) {
            if (mode != Mode.CALLING) return
            if (!vibrator.hasVibrator()) return
            val useSystem = Prefs.getUseSystemEffect(context)
            val vibMode = Prefs.getVibMode(context)
            if (!useSystem || vibMode.isEmpty()) {
                val strength = Prefs.getStrength(context)
                val pattern = Prefs.callPatternFor(Prefs.getPatternKey(context))
                try {
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(pattern, Prefs.amplitudeWave(pattern, strength), 0)
                    )
                } catch (e: Exception) {
                }
            }
        }
    }

    /** v3.17：單次預覽震動（系統效果一次／自訂 600ms 依強度）；CALLING 中跳過。 */
    fun previewOnce(): Boolean {
        synchronized(lock) {
            if (anyCalling) return false
            if (!vibrator.hasVibrator()) return true
            val useSystem = Prefs.getUseSystemEffect(context)
            val vibMode = Prefs.getVibMode(context)
            if (useSystem && vibMode.isNotEmpty()) {
                val effect = Prefs.predefinedEffectFor(vibMode)
                if (effect >= 0) {
                    try {
                        vibrator.vibrate(VibrationEffect.createPredefined(effect))
                    } catch (e: Exception) {
                    }
                }
            } else {
                val strength = Prefs.getStrength(context)
                try {
                    vibrator.vibrate(
                        VibrationEffect.createWaveform(longArrayOf(600), intArrayOf(strength), -1)
                    )
                } catch (e: Exception) {
                }
            }
            return true
        }
    }

    private fun stopRepeatLoop() {
        repeatRunnable?.let { repeatHandler.removeCallbacks(it) }
        repeatRunnable = null
    }
}
