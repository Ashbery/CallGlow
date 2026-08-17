package com.linewatch.watch

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 擴散漣漪（ui-spec v3.2/v3.3）：雷達脈衝風格，自中心向外擴散的 2~3 圈圓環。
 * v3.3（方案 A 結構同心修正）：本 View 置於 ringContainer 首位子層、Gravity.CENTER、
 * 尺寸 160dp → 漣漪圓心＝畫布中心＝頭像中心（物理同心，零座標換算）。
 * clipPath 以畫布中心為圓心，半徑＝min(96dp, 圓屏內切圓半徑−本視圖中心偏移)（runtime 換算，
 * 僅影響淡出距離，不影響同心）。scale 0.6→1.05、alpha 0.35→0、2.4s 循環、相位錯開 800ms。
 */
class RippleView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    init {
        // 防方形 dirty-region 殘影（clipChildren=false 大子層＋硬體渲染）：顯式透明背景強制獨立透明層
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
    }

    private class Ripple(val delay: Float, val color: Int)

    private val density = resources.displayMetrics.density
    private val ripples = listOf(
        Ripple(delay = 0f, color = 0xFF7C4DFF.toInt()),      // D17 銀河：深空紫
        Ripple(delay = 1f / 3f, color = 0xFF18FFFF.toInt()), // 星雲青
        Ripple(delay = 2f / 3f, color = 0xFFE040FB.toInt()), // 星雲洋紅
    )
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val clipPath = Path()

    private var progress = 0f
    private var animator: ValueAnimator? = null
    private var clipRadius = 0f
    private val baseRadius = 48f * density   // v3.9：48dp（scale 0.6→1.05 → 最大 50.4dp）

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateClipRadius(w, h)
    }

    /** 安全裁切圓半徑：min(96dp, 圓屏內切圓半徑 − 本視圖中心偏移)；layout 未穩定時下限 48dp。 */
    private fun updateClipRadius(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        val dm = resources.displayMetrics
        val inscribed = min(dm.widthPixels, dm.heightPixels) / 2f
        val loc = IntArray(2)
        getLocationInWindow(loc)
        val cx = loc[0] + w / 2f
        val cy = loc[1] + h / 2f
        val offX = cx - dm.widthPixels / 2f
        val offY = cy - dm.heightPixels / 2f
        val offset = sqrt(offX * offX + offY * offY)
        clipRadius = min(96f * density, inscribed - offset).coerceAtLeast(48f * density)
        clipPath.reset()
        clipPath.addCircle(w / 2f, h / 2f, clipRadius, Path.Direction.CW)
    }

    fun startRipples() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400L
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { a ->
                progress = a.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun stopRipples() {
        animator?.cancel()
        animator = null
        progress = 0f
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (animator == null || clipRadius <= 0f) return
        val save = canvas.save()
        canvas.clipPath(clipPath)
        val cx = width / 2f
        val cy = height / 2f
        for (r in ripples) {
            val p = (progress + r.delay) % 1f
            val radius = baseRadius * (0.6f + 0.45f * p)   // scale 0.6→1.05
            val alpha = 0.35f * (1f - p)
            ripplePaint.color = r.color
            ripplePaint.alpha = (alpha * 255f).toInt().coerceIn(0, 255)
            canvas.drawCircle(cx, cy, radius, ripplePaint)
        }
        canvas.restoreToCount(save)
    }

    override fun onDetachedFromWindow() {
        stopRipples()
        super.onDetachedFromWindow()
    }
}
