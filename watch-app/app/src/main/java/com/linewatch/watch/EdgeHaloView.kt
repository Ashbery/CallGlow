package com.linewatch.watch

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.SweepGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * D17 螢幕邊緣呼吸光環：沿圓屏最外圈一圈細光環（離邊緣 6dp），
 * 銀河 sweep 漸層（紫→靛→藍→青→洋紅），3s 呼吸（alpha 0.15→0.5）＋45s 慢速自轉。
 * 畫於星空之下、內容之上；clipPath 內切圓不滲出圓屏。
 */
class EdgeHaloView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
    }
    private val clipPath = Path()
    private var gradient: SweepGradient? = null
    private var ringRadius = 0f
    private var angle = 0f
    private var breathe: ObjectAnimator? = null
    private var rotator: ValueAnimator? = null

    private val colors = intArrayOf(
        0xFF7C4DFF.toInt(), 0xFF536DFE.toInt(), 0xFF29B6F6.toInt(),
        0xFF18FFFF.toInt(), 0xFFE040FB.toInt(), 0xFF7C4DFF.toInt(),
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val cx = w / 2f
        val cy = h / 2f
        val inscribed = min(w, h) / 2f
        ringRadius = (inscribed - 6f * density).coerceAtLeast(inscribed * 0.8f)
        gradient = SweepGradient(cx, cy, colors, null)
        clipPath.reset()
        clipPath.addCircle(cx, cy, inscribed, Path.Direction.CW)
        paint.shader = gradient
    }

    fun start() {
        if (breathe != null) return
        alpha = 0.15f
        breathe = ObjectAnimator.ofFloat(this, "alpha", 0.15f, 0.5f).apply {
            duration = 3000L
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
        rotator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 45_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                angle = a.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stop() {
        breathe?.cancel()
        breathe = null
        rotator?.cancel()
        rotator = null
        alpha = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (gradient == null || ringRadius <= 0f || rotator == null) return
        val save = canvas.save()
        canvas.clipPath(clipPath)
        canvas.rotate(angle, width / 2f, height / 2f)
        canvas.drawCircle(width / 2f, height / 2f, ringRadius, paint)
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
