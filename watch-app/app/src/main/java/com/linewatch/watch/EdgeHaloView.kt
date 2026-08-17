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
 * D17.7 現代流動光環（參考 Apple 活動環／OPPO 充電環的「光在環上流動」語言）：
 * - 寬柔光帶 12dp：極淡銀河底光（深度感，alpha 0x1A）
 * - 細亮環 2.5dp：全圈銀河 sweep 漸層（紫→藍→洋紅），40s 緩轉——現代感的「線」
 * - 流動光段：單一道 ~110° 柔光（兩端平滑漸隱、無硬頭尾），亮青白 6s/圈——「光在跑」
 * 整體 3s 呼吸（alpha 0.35→0.7）；clipPath 內切圓。
 */
class EdgeHaloView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    private val density = resources.displayMetrics.density
    private val dp = { v: Float -> v * density }

    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 12f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val flowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
    }

    private val clipPath = Path()
    private var bandGradient: SweepGradient? = null
    private var ringGradient: SweepGradient? = null
    private var flowGradient: SweepGradient? = null
    private var ringRadius = 0f
    private var baseAngle = 0f
    private var flowAngle = 0f
    private var breathe: ObjectAnimator? = null
    private var baseRotator: ValueAnimator? = null
    private var flowRotator: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val cx = w / 2f
        val cy = h / 2f
        val inscribed = min(w, h) / 2f
        ringRadius = inscribed - dp(2.5f)
        // 底光：極淡銀河全圈
        bandGradient = SweepGradient(cx, cy, intArrayOf(
            0x1A7C4DFF.toInt(), 0x1A29B6F6.toInt(), 0x1AE040FB.toInt(), 0x1A18FFFF.toInt(), 0x1A7C4DFF.toInt(),
        ), null)
        // 細亮環：中亮度的銀河全圈（光流跑在上面才有對比）
        ringGradient = SweepGradient(cx, cy, intArrayOf(
            0x667C4DFF.toInt(), 0x6629B6F6.toInt(), 0x66E040FB.toInt(), 0x6618FFFF.toInt(), 0x667C4DFF.toInt(),
        ), null)
        // 流動光段：~110° 柔光波瓣（0→峰值→0 平滑），亮青白
        flowGradient = SweepGradient(cx, cy, intArrayOf(
            0x00000000, 0x00000000, 0x66E8FBFF.toInt(), 0xCCE8FBFF.toInt(), 0xFF9BE8FF.toInt(),
            0xFFE8FBFF.toInt(), 0xCCE8FBFF.toInt(), 0x66E8FBFF.toInt(), 0x00000000, 0x00000000,
        ), floatArrayOf(0f, 0.30f, 0.36f, 0.42f, 0.48f, 0.50f, 0.58f, 0.64f, 0.70f, 1f))
        clipPath.reset()
        clipPath.addCircle(cx, cy, inscribed, Path.Direction.CW)
        bandPaint.shader = bandGradient
        ringPaint.shader = ringGradient
        flowPaint.shader = flowGradient
    }

    fun start() {
        if (breathe != null) return
        alpha = 0.5f
        breathe = ObjectAnimator.ofFloat(this, "alpha", 0.5f, 0.85f).apply {
            duration = 3000L
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
        baseRotator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 40_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { a -> baseAngle = a.animatedValue as Float; invalidate() }
            start()
        }
        flowRotator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 6_000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { a -> flowAngle = a.animatedValue as Float; invalidate() }
            start()
        }
    }

    fun stop() {
        breathe?.cancel()
        breathe = null
        baseRotator?.cancel()
        baseRotator = null
        flowRotator?.cancel()
        flowRotator = null
        alpha = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (baseRotator == null || ringRadius <= 0f) return
        val save = canvas.save()
        canvas.clipPath(clipPath)
        val cx = width / 2f
        val cy = height / 2f
        // 1) 底光帶（慢轉）
        canvas.save()
        canvas.rotate(baseAngle, cx, cy)
        canvas.drawCircle(cx, cy, ringRadius, bandPaint)
        canvas.restore()
        // 2) 細亮環（同慢轉）
        canvas.save()
        canvas.rotate(baseAngle, cx, cy)
        canvas.drawCircle(cx, cy, ringRadius, ringPaint)
        canvas.restore()
        // 3) 流動光段（快）
        canvas.save()
        canvas.rotate(flowAngle, cx, cy)
        canvas.drawCircle(cx, cy, ringRadius, flowPaint)
        canvas.restore()
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
