package com.linewatch.watch

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
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
 * D17.2 星海邊緣（使用者回饋：線條光環太單調 → 改一圈星海）——
 * 沿圓屏外圈一圈 70%–97% 半徑的環帶，無線條：
 * 1) 星雲底霧：兩道極淡 radial 光暈（紫/青，alpha ≤0.06）當「銀河霧氣」
 * 2) 微星 ×110：0.4–1.2dp 星點，白/淡紫/淡青/淡洋紅/淡金五色，各自閃爍
 * 3) 亮星 ×16：1.6–2.8dp 大星＋十字星芒（四向漸隱射線），慢速閃爍
 * 整體 3.5s 呼吸（alpha 0.2→0.6）＋ 48s 緩慢公轉（星海漂流感）。
 * clipPath 內切圓防滲出；固定種子可重現。
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

    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.6f)
        strokeCap = Paint.Cap.ROUND
    }
    private val clipPath = Path()

    private var glowGradients: List<RadialGradient> = emptyList()
    private var angle = 0f
    private var breathe: ObjectAnimator? = null
    private var rotator: ValueAnimator? = null

    private class Star(
        val angleRad: Float,
        val radiusRatio: Float,     // 0.70–0.97 屏半徑比例
        val sizeDp: Float,
        val baseAlpha: Float,
        val twinkleSpeed: Float,
        val color: Int,
        val bright: Boolean,       // 亮星（帶十字星芒）
    )

    private val stars = mutableListOf<Star>()

    // 星海五色（柔化：不刺眼的淡色星空）
    private val starColors = intArrayOf(
        0xFFFFFFFF.toInt(), 0xFFC9B8FF.toInt(), 0xFFA8F7FF.toInt(),
        0xFFFFB8F0.toInt(), 0xFFFFE9B8.toInt(),
    )

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val cx = w / 2f
        val cy = h / 2f
        val inscribed = min(w, h) / 2f
        // 星雲底霧：外圈兩團淡紫/淡青光暈（radial、極低 alpha）
        glowGradients = listOf(
            RadialGradient(cx + inscribed * 0.25f, cy - inscribed * 0.25f, inscribed * 0.9f,
                intArrayOf(0x0F7C4DFF.toInt(), 0x067C4DFF.toInt(), 0x007C4DFF.toInt()),
                null, Shader.TileMode.CLAMP),
            RadialGradient(cx - inscribed * 0.3f, cy + inscribed * 0.2f, inscribed * 0.9f,
                intArrayOf(0x0D18FFFF.toInt(), 0x0518FFFF.toInt(), 0x0018FFFF.toInt()),
                null, Shader.TileMode.CLAMP),
        )
        clipPath.reset()
        clipPath.addCircle(cx, cy, inscribed, Path.Direction.CW)
        // 星點生成（固定種子）——D17.3：密集在原本光環那一圈（半徑 0.84–0.97 窄帶，貼螢幕邊）
        val rnd = Random(20260817)
        stars.clear()
        // 微星 ×230（窄帶密集）
        for (i in 0 until 230) {
            stars += Star(
                angleRad = rnd.nextFloat() * 2f * PI.toFloat(),
                radiusRatio = 0.84f + rnd.nextFloat() * 0.13f,
                sizeDp = 0.35f + rnd.nextFloat() * 0.75f,
                baseAlpha = 0.25f + rnd.nextFloat() * 0.45f,
                twinkleSpeed = 1.0f + rnd.nextFloat() * 2.8f,
                color = starColors[i % starColors.size],
                bright = false,
            )
        }
        // 亮星 ×24（十字星芒，同窄帶）
        for (i in 0 until 24) {
            stars += Star(
                angleRad = rnd.nextFloat() * 2f * PI.toFloat(),
                radiusRatio = 0.85f + rnd.nextFloat() * 0.10f,
                sizeDp = 1.5f + rnd.nextFloat() * 1.1f,
                baseAlpha = 0.4f + rnd.nextFloat() * 0.4f,
                twinkleSpeed = 0.5f + rnd.nextFloat() * 1.2f,
                color = starColors[(i + 2) % starColors.size],
                bright = true,
            )
        }
    }

    fun start() {
        if (breathe != null) return
        alpha = 0.2f
        breathe = ObjectAnimator.ofFloat(this, "alpha", 0.2f, 0.6f).apply {
            duration = 3500L
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
            start()
        }
        rotator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 48_000L
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
        if (rotator == null || stars.isEmpty()) return
        val save = canvas.save()
        canvas.clipPath(clipPath)
        val cx = width / 2f
        val cy = height / 2f
        val inscribed = min(width, height) / 2f
        // 1) 星雲底霧
        glowGradients.forEach { g ->
            glowPaint.shader = g
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint)
        }
        glowPaint.shader = null
        // 2) 星點（隨公轉角）
        val rad = Math.toRadians(angle.toDouble()).toFloat()
        val now = SystemClock.uptimeMillis() / 1000f
        for (s in stars) {
            val a = s.angleRad + rad
            val r = inscribed * s.radiusRatio
            val x = cx + cos(a) * r
            val y = cy + sin(a) * r
            val tw = 0.55f + 0.45f * sin(now * s.twinkleSpeed + s.angleRad * 9f)
            val alpha = (s.baseAlpha * tw * 255f).toInt().coerceIn(0, 255)
            starPaint.color = s.color
            starPaint.alpha = alpha
            val size = dp(s.sizeDp)
            canvas.drawCircle(x, y, size, starPaint)
            if (s.bright) {
                // 十字星芒：四向射線，長度隨閃爍相位伸縮
                val rayLen = size * (2.2f + 1.8f * tw)
                rayPaint.color = s.color
                rayPaint.alpha = (alpha * 0.55f).toInt().coerceIn(0, 255)
                canvas.drawLine(x - rayLen, y, x + rayLen, y, rayPaint)
                canvas.drawLine(x, y - rayLen, x, y + rayLen, rayPaint)
            }
        }
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
