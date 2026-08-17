package com.linewatch.watch

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 星空主背景（ui-spec v3.4）：Canvas＋ValueAnimator，純 AOSP。
 * - 40~50 星點（1~2px、白/淡綠混色、alpha 0.2~0.8），正弦 twinkle（週期 1.5~4s 隨機）＋
 *   極緩慢上漂（~0.3px/幀），漂出安全圓即於底部重生成
 * - 星點僅分布於內切圓安全區（clipPath），中央內容區留白不擁擠
 * - 僅 CALLING 動畫；靜止態繪製極淡星空（alpha 0.15）
 */
class StarfieldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    init {
        // 防方形 dirty-region 殘影：顯式透明背景強制獨立透明層
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    private class Star(
        var x: Float,
        var y: Float,
        val r: Float,
        val baseAlpha: Float,
        val phase: Float,
        val periodMs: Float,
        val color: Int,
        var vx: Float,   // drift 時重生成會更新（故 var）
    )

    private val density = resources.displayMetrics.density
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private val stars = mutableListOf<Star>()
    private val rnd = Random(20260817)   // 固定種子：每次啟動星空布局一致（可重現）

    private var elapsedMs = 0f
    private var animator: ValueAnimator? = null
    private var starsReady = false

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        clipPath.reset()
        clipPath.addCircle(w / 2f, h / 2f, minOf(w, h) / 2f, Path.Direction.CW)
        if (!starsReady) {
            generateStars(w, h)
            starsReady = true
        }
    }

    /** 40~50 星點：僅內切圓內、避開中央內容區（頭像/標題/名字帶）。 */
    private fun generateStars(w: Int, h: Int) {
        val count = 40 + rnd.nextInt(11)   // 40~50
        val cx = w / 2f
        val cy = h / 2f
        val radius = minOf(w, h) / 2f
        val contentHalfW = w * 0.42f
        val contentTop = h * 0.32f
        val contentBottom = h * 0.72f
        var tries = 0
        while (stars.size < count && tries < count * 60) {
            tries++
            val x = rnd.nextFloat() * w
            val y = rnd.nextFloat() * h
            val dx = x - cx
            val dy = y - cy
            if (sqrt(dx * dx + dy * dy) > radius * 0.94f) continue   // 內切圓內留邊
            if (y in contentTop..contentBottom && kotlin.math.abs(dx) < contentHalfW) continue  // 中央內容區留白
            if (y < h * 0.12f) continue   // 頂部細窄區避開
            val sizePx = (1f + rnd.nextFloat()) * density          // 1~2dp 星點
            val greenish = rnd.nextFloat() < 0.4f
            val color = if (greenish) 0xFF9FE8B0.toInt() else Color.WHITE   // 淡綠或白
            stars.add(
                Star(
                    x = x,
                    y = y,
                    r = sizePx,
                    baseAlpha = 0.2f + rnd.nextFloat() * 0.6f,     // 0.2~0.8
                    phase = rnd.nextFloat() * 2f * PI.toFloat(),
                    periodMs = 1500f + rnd.nextFloat() * 2500f,     // 1.5~4s
                    color = color,
                    vx = (rnd.nextFloat() - 0.5f) * 0.06f * density, // 水平微漂
                )
            )
        }
    }

    fun startStars() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 16_000L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { a ->
                elapsedMs += a.animatedFraction * 16f   // 約 16ms/幀
                driftStars()
                invalidate()
            }
            start()
        }
    }

    fun stopStars() {
        animator?.cancel()
        animator = null
        invalidate()
    }

    /** 極緩慢上漂＋水平微漂；漂出安全圓 → 於底部重生成。 */
    private fun driftStars() {
        if (width <= 0 || height <= 0) return
        val cx = width / 2f
        val cy = height / 2f
        val radius = minOf(width, height) / 2f
        val contentTop = height * 0.32f
        val contentBottom = height * 0.72f
        val contentHalfW = width * 0.42f
        for (s in stars) {
            s.y -= 0.3f * density
            s.x += s.vx
            val dx = s.x - cx
            val dy = s.y - cy
            val out = sqrt(dx * dx + dy * dy) > radius * 0.94f || s.y < height * 0.06f
            if (out) {
                // 重生成：底部區域、避開中央內容帶
                s.y = height * (0.78f + rnd.nextFloat() * 0.18f)
                s.x = cx + (rnd.nextFloat() - 0.5f) * radius * 1.7f
                if (kotlin.math.abs(s.x - cx) < contentHalfW && s.y in contentTop..contentBottom) {
                    s.x = cx + contentHalfW * (if (rnd.nextBoolean()) 1f else -1f)
                }
                s.vx = (rnd.nextFloat() - 0.5f) * 0.06f * density
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!starsReady) return
        val save = canvas.save()
        canvas.clipPath(clipPath)
        val animated = animator != null
        for (s in stars) {
            val alpha = if (animated) {
                val phase = s.phase + elapsedMs * (2f * PI.toFloat() / s.periodMs)
                s.baseAlpha * (0.5f + 0.5f * sin(phase))
            } else {
                s.baseAlpha * 0.15f   // 靜止態：極淡星空
            }
            starPaint.color = s.color
            starPaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawCircle(s.x, s.y, s.r, starPaint)
        }
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        stopStars()
        super.onDetachedFromWindow()
    }
}
