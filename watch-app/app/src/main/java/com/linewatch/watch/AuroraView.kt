package com.linewatch.watch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * 極光光暈（ui-spec v3.2）：底部上來的柔和大面積 radial 綠光。
 * 中心於屏高 78%、半徑 75% 屏寬、色 alpha ≤0.10；由外層 view alpha 做 3.5s 緩慢呼吸。
 * canvas clipPath＝螢幕內切圓（FLAG_ROUND 可視區），光暈不滲出圓屏外緣。純 AOSP。
 */
class AuroraView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val clipPath = Path()
    private var gradient: RadialGradient? = null

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        gradient = RadialGradient(
            w / 2f,
            h * 0.78f,
            maxOf(w, h) * 0.75f,
            intArrayOf(
                0x1A06C755.toInt(),   // 中心 10% 綠
                0x0D06C755.toInt(),
                0x0006C755.toInt(),
            ),
            null,
            Shader.TileMode.CLAMP
        )
        paint.shader = gradient
        // 內切圓裁切（圓屏可視區）
        clipPath.reset()
        clipPath.addCircle(w / 2f, h / 2f, min(w, h) / 2f, Path.Direction.CW)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (gradient == null) return
        val save = canvas.save()
        canvas.clipPath(clipPath)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        canvas.restoreToCount(save)
    }
}
