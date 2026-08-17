package com.linewatch.watch

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.SweepGradient
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.min
import kotlin.random.Random

/**
 * D17.1 銀河邊緣光帶（大廠質感）：不再是單一細線，而是四層組合——
 * 1) 寬柔光帶（10dp、alpha 0.10）：星雲底光，緊貼螢幕邊緣（離邊 1.5dp）
 * 2) 分段亮弧（4dp、sweep 漸層夾透明缺口）：星河感——紫/青/洋紅亮弧交錯、有暗有亮
 * 3) 細亮核心（2dp、同分段漸層）：銳利的高光線
 * 4) 星點：28 顆沿環分布的星光（大小/相位/色隨機、閃爍），隨光帶一起公轉
 * 整體 3s 呼吸（alpha 0.15→0.55）＋40s 自轉一圈。clipPath 內切圓防滲出。
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

    // 四層畫筆
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 10f * density
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val clipPath = Path()
    private var bandGradient: SweepGradient? = null      // 全圈柔光（無缺口）
    private var arcGradient: SweepGradient? = null       // 分段亮弧（夾透明缺口 → 星河感）
    private var coreGradient: SweepGradient? = null

    private var bandRadius = 0f
    private var coreRadius = 0f
    private var angle = 0f
    private var breathe: ObjectAnimator? = null
    private var rotator: ValueAnimator? = null

    private class Sparkle(val angleRad: Float, val radiusOffDp: Float, val sizeDp: Float, val baseAlpha: Float, val twinkleSpeed: Float, val color: Int)
    private val sparkles = mutableListOf<Sparkle>()

    private val galaxyColors = intArrayOf(
        0xFF7C4DFF.toInt(), 0xFF536DFE.toInt(), 0xFF29B6F6.toInt(),
        0xFF18FFFF.toInt(), 0xFFE040FB.toInt(),
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val cx = w / 2f
        val cy = h / 2f
        val inscribed = min(w, h) / 2f
        bandRadius = (inscribed - dp(1.5f))          // 柔光帶貼近螢幕邊緣
        coreRadius = (inscribed - dp(3f))            // 亮弧/核心稍內縮
        // 柔光帶：全圈銀河漸層（低 alpha 打底）
        bandGradient = SweepGradient(cx, cy, intArrayOf(
            0x1A7C4DFF.toInt(), 0x1A29B6F6.toInt(), 0x1AE040FB.toInt(), 0x1A18FFFF.toInt(), 0x1A7C4DFF.toInt(),
        ), null)
        // 亮弧：三段星雲弧（紫/青/洋紅）＋三段透明缺口 → 星河不連續感
        arcGradient = SweepGradient(cx, cy, intArrayOf(
            0xAA7C4DFF.toInt(), 0x0018FFFF.toInt(), 0x99E040FB.toInt(), 0x0029B6F6.toInt(),
            0xAA18FFFF.toInt(), 0x00536DFE.toInt(), 0xAA7C4DFF.toInt(),
        ), null)
        coreGradient = SweepGradient(cx, cy, intArrayOf(
            0xCC7C4DFF.toInt(), 0x0018FFFF.toInt(), 0xCCE040FB.toInt(), 0x0029B6F6.toInt(),
            0xCC18FFFF.toInt(), 0x00536DFE.toInt(), 0xCC7C4DFF.toInt(),
        ), null)
        clipPath.reset()
        clipPath.addCircle(cx, cy, inscribed, Path.Direction.CW)
        bandPaint.shader = bandGradient
        arcPaint.shader = arcGradient
        corePaint.shader = coreGradient
        // 星點：固定種子可重現
        val rnd = Random(20260817)
        sparkles.clear()
        for (i in 0 until 28) {
            sparkles += Sparkle(
                angleRad = (i * 2f * PI / 28f).toFloat() + (rnd.nextFloat() - 0.5f) * 0.2f,
                radiusOffDp = (rnd.nextFloat() - 0.5f) * 7f,
                sizeDp = 0.7f + rnd.nextFloat() * 1.6f,
                baseAlpha = 0.25f + rnd.nextFloat() * 0.5f,
                twinkleSpeed = 1.2f + rnd.nextFloat() * 2.6f,
                color = galaxyColors[i % galaxyColors.size],
            )
        }
    }

    fun start() {
        if (breathe != null) return
        alpha = 0.15f
        breathe = ObjectAnimator.ofFloat(this, "alpha", 0.15f, 0.55f).apply {
            duration = 3000L
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
        rotator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 40_000L
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
        if (rotator == null || coreRadius <= 0f) return
        val save = canvas.save()
        canvas.clipPath(clipPath)
        val cx = width / 2f
        val cy = height / 2f
        canvas.rotate(angle, cx, cy)
        // 1) 柔光帶 → 2) 亮弧 → 3) 核心線
        canvas.drawCircle(cx, cy, bandRadius, bandPaint)
        canvas.drawCircle(cx, cy, coreRadius, arcPaint)
        canvas.drawCircle(cx, cy, coreRadius, corePaint)
        // 4) 星點閃爍
        val now = SystemClock.uptimeMillis() / 1000f
        for (s in sparkles) {
            val a = s.angleRad + Math.toRadians(angle.toDouble()).toFloat()
            val r = coreRadius + dp(s.radiusOffDp)
            val x = cx + cos(a) * r
            val y = cy + sin(a) * r
            val twinkle = 0.5f + 0.5f * sin(now * s.twinkleSpeed + s.angleRad * 7f)
            starPaint.color = s.color
            starPaint.alpha = (s.baseAlpha * twinkle * 255f).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, dp(s.sizeDp), starPaint)
        }
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
