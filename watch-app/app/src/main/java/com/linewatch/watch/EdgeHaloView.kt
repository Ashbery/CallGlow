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
 * D17.5 3D 銀河星盤（使用者提議 3D 動效）：
 * 星帶是「傾斜 32° 的銀河盤面」——3D 點投影到 2D（Canvas 數學，API 30 無 GPU 依賴）：
 * - 每顆星在盤面環帶上有 3D 座標（角度 θ、半徑 r、厚度 z 抖動）
 * - 盤面繞 X 軸傾斜 32° → 投影成橢圓；繞盤面軸 48s 自轉
 * - 景深：靠近觀者的星（z'>0）放大（至 1.3×）＋變亮；遠處縮小（0.6×）＋變暗
 * - 繪製按深度排序（遠→近），前後遮蔽正確 → 真 3D 立體感
 * 星群：微星 ×250／亮星 ×28（十字星芒＋光暈）／高亮星 ×8（純白核＋大光暈）。
 * 整體 3.5s 呼吸（alpha 0.2→0.6）；clipPath 內切圓防滲出；固定種子可重現。
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

    private val tiltDeg = 32f   // 盤面傾角：32° 斜躺的銀河盤

    private class Star(
        val angleRad: Float,
        val radiusRatio: Float,     // 0.90–0.99 屏半徑比例（貼螢幕邊）
        val zJitter: Float,         // 盤面厚度抖動 -1..1（±5% 屏半徑）
        val sizeDp: Float,
        val baseAlpha: Float,
        val twinkleSpeed: Float,
        val color: Int,
        val bright: Boolean,       // 亮星（十字星芒＋光暈）
        val highlight: Boolean,    // 高亮星（純白核＋大光暈＋長星芒）
    )

    private val stars = mutableListOf<Star>()

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
        // 星群（D17.4 參數：貼邊窄帶＋高亮）
        val rnd = Random(20260817)
        stars.clear()
        for (i in 0 until 250) {
            stars += Star(
                angleRad = rnd.nextFloat() * 2f * PI.toFloat(),
                radiusRatio = 0.90f + rnd.nextFloat() * 0.09f,
                zJitter = rnd.nextFloat() * 2f - 1f,
                sizeDp = 0.4f + rnd.nextFloat() * 0.8f,
                baseAlpha = 0.35f + rnd.nextFloat() * 0.45f,
                twinkleSpeed = 1.0f + rnd.nextFloat() * 2.8f,
                color = starColors[i % starColors.size],
                bright = false,
                highlight = false,
            )
        }
        for (i in 0 until 28) {
            stars += Star(
                angleRad = rnd.nextFloat() * 2f * PI.toFloat(),
                radiusRatio = 0.91f + rnd.nextFloat() * 0.07f,
                zJitter = rnd.nextFloat() * 2f - 1f,
                sizeDp = 1.6f + rnd.nextFloat() * 1.1f,
                baseAlpha = 0.55f + rnd.nextFloat() * 0.35f,
                twinkleSpeed = 0.5f + rnd.nextFloat() * 1.2f,
                color = starColors[(i + 2) % starColors.size],
                bright = true,
                highlight = false,
            )
        }
        for (i in 0 until 8) {
            stars += Star(
                angleRad = rnd.nextFloat() * 2f * PI.toFloat(),
                radiusRatio = 0.92f + rnd.nextFloat() * 0.05f,
                zJitter = rnd.nextFloat() * 2f - 1f,
                sizeDp = 2.2f + rnd.nextFloat() * 1.0f,
                baseAlpha = 0.8f + rnd.nextFloat() * 0.2f,
                twinkleSpeed = 0.4f + rnd.nextFloat() * 0.6f,
                color = 0xFFFFFFFF.toInt(),
                bright = true,
                highlight = true,
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

    // 3D 投影快取（每幀重建，避免物件分配）
    private data class Projected(var depth: Float, var sx: Float, var sy: Float, var sizePx: Float, var alpha: Int, var color: Int, var bright: Boolean, var highlight: Boolean, var tw: Float)
    private val projected = ArrayList<Projected>(286)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (rotator == null || stars.isEmpty()) return
        val save = canvas.save()
        canvas.clipPath(clipPath)
        val cx = width / 2f
        val cy = height / 2f
        val inscribed = min(width, height) / 2f
        // 1) 星雲底霧（隨傾角：橢圓分佈的柔光）
        glowGradients.forEach { g ->
            glowPaint.shader = g
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), glowPaint)
        }
        glowPaint.shader = null
        // 2) 3D 投影
        val tiltRad = Math.toRadians(tiltDeg.toDouble()).toFloat()
        val cosT = cos(tiltRad)
        val sinT = sin(tiltRad)
        val spin = Math.toRadians(angle.toDouble()).toFloat()
        val now = SystemClock.uptimeMillis() / 1000f
        projected.clear()
        for (s in stars) {
            val th = s.angleRad + spin
            val r = inscribed * s.radiusRatio
            val x3 = cos(th) * r
            val y3 = sin(th) * r
            val z3 = s.zJitter * inscribed * 0.05f
            // 繞 X 軸傾斜（盤面斜躺）
            val yp = y3 * cosT - z3 * sinT
            val zp = y3 * sinT + z3 * cosT   // zp>0 = 靠近觀者
            val depth = zp / inscribed       // -1..1
            val pscale = 1f + 0.3f * depth   // 近大遠小（0.7–1.3）
            val sx = cx + x3 * pscale
            val sy = cy + yp * pscale
            val tw = 0.55f + 0.45f * sin(now * s.twinkleSpeed + s.angleRad * 9f)
            val sizePx = dp(s.sizeDp) * (0.6f + 0.7f * (depth + 1f) / 2f) * (0.9f + 0.2f * tw)
            val alpha = (s.baseAlpha * tw * (0.45f + 0.55f * (depth + 1f) / 2f) * 255f).toInt().coerceIn(0, 255)
            projected += Projected(depth, sx, sy, sizePx, alpha, s.color, s.bright, s.highlight, tw)
        }
        // 遠 → 近排序（後方星先畫，前方星蓋上）
        projected.sortBy { it.depth }
        for (p in projected) {
            if (p.bright) {
                val haloR = p.sizePx * (if (p.highlight) 4.5f else 3f)
                glowPaint.color = p.color
                glowPaint.alpha = (p.alpha * (if (p.highlight) 0.30f else 0.18f)).toInt().coerceIn(0, 255)
                canvas.drawCircle(p.sx, p.sy, haloR, glowPaint)
            }
            starPaint.color = p.color
            starPaint.alpha = p.alpha
            canvas.drawCircle(p.sx, p.sy, p.sizePx, starPaint)
            if (p.bright) {
                val rayLen = p.sizePx * (if (p.highlight) 3.4f + 2.0f * p.tw else 2.2f + 1.8f * p.tw)
                rayPaint.color = p.color
                rayPaint.alpha = (p.alpha * (if (p.highlight) 0.8f else 0.55f)).toInt().coerceIn(0, 255)
                canvas.drawLine(p.sx - rayLen, p.sy, p.sx + rayLen, p.sy, rayPaint)
                canvas.drawLine(p.sx, p.sy - rayLen, p.sx, p.sy + rayLen, rayPaint)
            }
            if (p.highlight) {
                starPaint.color = 0xFFFFFFFF.toInt()
                starPaint.alpha = p.alpha
                canvas.drawCircle(p.sx, p.sy, p.sizePx * 0.55f, starPaint)
            }
        }
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
