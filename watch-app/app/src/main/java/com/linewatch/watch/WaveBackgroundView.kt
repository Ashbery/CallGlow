package com.linewatch.watch

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

/**
 * 動態背景：LINE 綠波浪自下而上緩慢流動（ui-spec v3 動態背景；純 AOSP Canvas＋ValueAnimator）。
 * - 僅來電態繪製與動畫（startWaves）；未接/斷線停止（stopWaves → 不繪製，背景回到靜態）
 * - 2~3 條半透明正弦波紋、~9s/週期、alpha ≤0.14，氛圍用途不喧賓奪主
 */
class WaveBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private class Wave(
        val amp: Float,
        val freq: Float,
        val yBase: Float,
        val alpha: Float,
        val phaseMul: Float,
    )

    private val density = resources.displayMetrics.density
    private val waves = listOf(
        Wave(amp = 6f * density, freq = 0.020f, yBase = 0.60f, alpha = 0.10f, phaseMul = 0.6f),
        Wave(amp = 9f * density, freq = 0.016f, yBase = 0.70f, alpha = 0.14f, phaseMul = 0.4f),
        Wave(amp = 7f * density, freq = 0.024f, yBase = 0.80f, alpha = 0.08f, phaseMul = 0.8f),
    )
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = 0xFF06C755.toInt()
    }

    private var phase = 0f
    private var animator: ValueAnimator? = null

    /** 開始波浪動畫（來電態）。 */
    fun startWaves() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 2f * PI.toFloat()).apply {
            duration = 9_000L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { a ->
                phase = a.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /** 停止波浪（未接/斷線態 → 靜態背景）。 */
    fun stopWaves() {
        animator?.cancel()
        animator = null
        phase = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (animator == null) return   // 未啟動 → 不繪製（靜態背景）
        val w = width.toFloat()
        val h = height.toFloat()
        val progress = phase / (2f * PI.toFloat())
        val drift = (progress * h * 0.12f) % (h * 0.35f)   // 每週期緩慢上移 12% 屏高（模 35% 防飄出）
        for (wave in waves) {
            wavePaint.alpha = (wave.alpha * 255f).toInt()
            val path = Path()
            var x = 0f
            while (x <= w) {
                val y = h * wave.yBase - drift + wave.amp * sin(wave.freq * x + phase * wave.phaseMul)
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += 6f * density
            }
            canvas.drawPath(path, wavePaint)
        }
    }

    override fun onDetachedFromWindow() {
        stopWaves()
        super.onDetachedFromWindow()
    }
}
