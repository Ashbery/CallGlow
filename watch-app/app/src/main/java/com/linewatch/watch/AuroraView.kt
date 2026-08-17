package com.linewatch.watch

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * 銀河極光背景（D17 改寫，ui-spec v3.2 原件）：三團柔光暈（深空紫下方／星雲青左上／洋紅右），
 * 各 alpha ≤0.10，繞屏中心 30s 緩慢旋轉一圈——星雲流動的太空感。
 * canvas clipPath＝螢幕內切圓；純 AOSP（API 30 無 RenderEffect，radial 即柔光）。
 */
class AuroraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    init {
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private var gradients: List<RadialGradient> = emptyList()
    private var angle = 0f
    private var animator: ValueAnimator? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        val maxR = maxOf(w, h) * 0.75f
        gradients = listOf(
            // 深空紫：中心於下方（屏高 85%）
            RadialGradient(w / 2f, h * 0.85f, maxR,
                intArrayOf(0x1A7C4DFF.toInt(), 0x0D536DFE.toInt(), 0x007C4DFF.toInt()),
                null, Shader.TileMode.CLAMP),
            // 星雲青：左上
            RadialGradient(w * 0.2f, h * 0.3f, maxR * 0.8f,
                intArrayOf(0x1418FFFF.toInt(), 0x0A29B6F6.toInt(), 0x0018FFFF.toInt()),
                null, Shader.TileMode.CLAMP),
            // 星雲洋紅：右下
            RadialGradient(w * 0.8f, h * 0.75f, maxR * 0.8f,
                intArrayOf(0x14E040FB.toInt(), 0x0A7C4DFF.toInt(), 0x00E040FB.toInt()),
                null, Shader.TileMode.CLAMP),
        )
        clipPath.reset()
        clipPath.addCircle(w / 2f, h / 2f, min(w, h) / 2f, Path.Direction.CW)
    }

    fun start() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 30_000L   // D17：30s 一圈（星雲緩流）
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
        animator?.cancel()
        animator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (gradients.isEmpty()) return
        val save = canvas.save()
        canvas.clipPath(clipPath)
        // 自轉：繞屏中心旋轉畫布後畫三團固定位置光暈
        canvas.rotate(angle, width / 2f, height / 2f)
        gradients.forEach { g ->
            paint.shader = g
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
        paint.shader = null
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }
}
