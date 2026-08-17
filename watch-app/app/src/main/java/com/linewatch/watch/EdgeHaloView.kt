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
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * D17.6 現代光環（使用者：回歸光環＋更帥更現代）——多層光環＋雙向軌道彗星：
 * 1) 寬柔光帶 10dp：貼螢幕邊的全圈銀河柔光（星雲底光，alpha 低）
 * 2) 分段亮弧 4dp：紫/青/洋紅三段亮弧＋透明缺口（活動環分段感，sweep 漸層）
 * 3) 細亮核心 2dp：銳利高光線（同分段漸層）
 * 4) 軌道彗星 ×2：光環上的亮點＋漸隱拖尾——順時針（青白，12s/圈）、逆時針（洋紅，9s/圈），
 *    像衛星繞行，頭部大光暈
 * 5) 微星 ×10：環上少量閃爍星點（點綴不搶戲）
 * 整體 3s 呼吸（alpha 0.25→0.6）＋ 40s 光環本體緩轉；clipPath 內切圓。
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
        strokeWidth = 10f * density
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val tailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * density
        strokeCap = Paint.Cap.ROUND
    }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val clipPath = Path()
    private var bandGradient: SweepGradient? = null
    private var arcGradient: SweepGradient? = null
    private var coreGradient: SweepGradient? = null

    private var bandRadius = 0f
    private var coreRadius = 0f
    private var angle = 0f
    private var breathe: ObjectAnimator? = null
    private var rotator: ValueAnimator? = null

    private class Twinkle(val angleRad: Float, val sizeDp: Float, val baseAlpha: Float, val speed: Float, val color: Int)
    private val twinkles = mutableListOf<Twinkle>()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val cx = w / 2f
        val cy = h / 2f
        val inscribed = min(w, h) / 2f
        bandRadius = inscribed - dp(1.5f)
        coreRadius = inscribed - dp(3f)
        bandGradient = SweepGradient(cx, cy, intArrayOf(
            0x1A7C4DFF.toInt(), 0x1A29B6F6.toInt(), 0x1AE040FB.toInt(), 0x1A18FFFF.toInt(), 0x1A7C4DFF.toInt(),
        ), null)
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
        val rnd = Random(20260818)
        twinkles.clear()
        val colors = intArrayOf(
            0xFFFFFFFF.toInt(), 0xFFC9B8FF.toInt(), 0xFFA8F7FF.toInt(), 0xFFFFE9B8.toInt(),
        )
        for (i in 0 until 10) {
            twinkles += Twinkle(
                angleRad = rnd.nextFloat() * 2f * PI.toFloat(),
                sizeDp = 0.5f + rnd.nextFloat() * 0.9f,
                baseAlpha = 0.3f + rnd.nextFloat() * 0.4f,
                speed = 1.2f + rnd.nextFloat() * 2.4f,
                color = colors[i % colors.size],
            )
        }
    }

    fun start() {
        if (breathe != null) return
        alpha = 0.25f
        breathe = ObjectAnimator.ofFloat(this, "alpha", 0.25f, 0.6f).apply {
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
        // 光環本體（緩轉）
        canvas.rotate(angle, cx, cy)
        canvas.drawCircle(cx, cy, bandRadius, bandPaint)
        canvas.drawCircle(cx, cy, coreRadius, arcPaint)
        canvas.drawCircle(cx, cy, coreRadius, corePaint)
        canvas.restoreToCount(save)

        // 微星（跟本體同轉）
        val now = SystemClock.uptimeMillis() / 1000f
        val rad = Math.toRadians(angle.toDouble()).toFloat()
        for (t in twinkles) {
            val a = t.angleRad + rad
            val x = cx + cos(a) * coreRadius
            val y = cy + sin(a) * coreRadius
            val tw = 0.55f + 0.45f * sin(now * t.speed + t.angleRad * 7f)
            starPaint.color = t.color
            starPaint.alpha = (t.baseAlpha * tw * 255f).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, dp(t.sizeDp), starPaint)
        }

        // 軌道彗星 ×2（與光環反向/同向不同速 → 衛星繞行感）
        drawComet(canvas, cx, cy, now, clockwise = true, periodS = 12f, phase = 0f,
            color = 0xFFA8F7FF.toInt(), headSizeDp = 2.6f, tailLenDeg = 55f)
        drawComet(canvas, cx, cy, now, clockwise = false, periodS = 9f, phase = 170f,
            color = 0xFFFF9BF5.toInt(), headSizeDp = 2.4f, tailLenDeg = 65f)
    }

    /** 軌道彗星：頭部亮點＋光暈＋沿環漸隱拖尾（分段畫弧，alpha 遞減）。 */
    private fun drawComet(
        canvas: Canvas, cx: Float, cy: Float, now: Float,
        clockwise: Boolean, periodS: Float, phase: Float, color: Int,
        headSizeDp: Float, tailLenDeg: Float,
    ) {
        val headAngle = (now / periodS * 360f + phase) % 360f
        val dir = if (clockwise) 1f else -1f
        // 拖尾：headAngle 向後 tailLenDeg，分 14 段漸隱
        val segs = 14
        for (i in 0 until segs) {
            val f = i / segs.toFloat()
            val start = headAngle - dir * (f * tailLenDeg)
            val sweep = -dir * (tailLenDeg / segs)
            val alpha = ((1f - f) * 0.55f * 255f).toInt()
            tailPaint.color = color
            tailPaint.alpha = alpha
            canvas.drawArc(
                cx - coreRadius, cy - coreRadius, cx + coreRadius, cy + coreRadius,
                start, sweep, false, tailPaint,
            )
        }
        // 頭部：光暈＋亮核
        val hx = cx + cos(Math.toRadians(headAngle.toDouble())).toFloat() * coreRadius
        val hy = cy + sin(Math.toRadians(headAngle.toDouble())).toFloat() * coreRadius
        headGlowPaint.color = color
        headGlowPaint.alpha = 0x55
        canvas.drawCircle(hx, hy, dp(headSizeDp) * 3.2f, headGlowPaint)
        headPaint.color = 0xFFFFFFFF.toInt()
        headPaint.alpha = 0xFF
        canvas.drawCircle(hx, hy, dp(headSizeDp), headPaint)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
