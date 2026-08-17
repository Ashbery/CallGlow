package com.linewatch.watch

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * SYSTEM_ALERT_WINDOW overlay 備援顯示。
 * docs/probe-report.md P7 實測：手錶 BAL 受限，連 FGS 進程的 startActivity 都會被系統 Abort
 * （ActivityTaskManager: Abort background activity starts）→ overlay 為主備援路徑。
 *
 * 與 IncomingCallActivity 相同視覺（v3：雙層光圈＋頭像發光＋背景 radial＋文字微光＋首字頭像/T8 真頭像）：
 * - CALL：特效全開循環；持續顯示直到 end（service 呼叫 dismiss）
 * - MISSED／DISCONNECTED：特效靜止為低 alpha 常數，8s 自動消失
 * - 震動一律由 BlePeripheralService 負責，本類別只管顯示
 * - 需授予「顯示在其他應用程式上層」；未授權時靜默失敗（Activity 路徑仍為優先路徑）
 *   測試授權：adb shell appops set com.linewatch.watch SYSTEM_ALERT_WINDOW allow
 */
object OverlayHelper {

    enum class Mode { CALL, MISSED, DISCONNECTED }

    private const val AUTO_DISMISS_MS = 8_000L   // D15：未接/斷線 overlay 螢幕持續亮時 8s 自動消失（右滑可提前）
    private const val COLOR_LINE_GREEN = 0xFF06C755.toInt()
    private const val COLOR_ALERT = 0xFFFF8A65.toInt()
    private const val COLOR_TEXT_PRIMARY = 0xFFFFFFFF.toInt()
    private const val COLOR_TEXT_SECONDARY = 0xFFB0B0B0.toInt()

    private val handler = Handler(Looper.getMainLooper())
    private var root: FrameLayout? = null

    /** v3.10 下滑關閉回呼（service onCreate 註冊 → endCall(false) 停震；onDestroy 清除）。 */
    var dismissListener: (() -> Unit)? = null

    private var overlayParams: WindowManager.LayoutParams? = null
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragging = false
    private var dragAnimator: ValueAnimator? = null
    private var currentMode: Mode? = null
    private var lastOverlayX = Float.MAX_VALUE
    private var lastMoveMs = 0L
    private var contentContainer: LinearLayout? = null
    private var overlayWm: WindowManager? = null
    private var dismissRunnable: Runnable? = null
    private val activeAnimators = mutableListOf<ObjectAnimator>()
    private val textPulseRunnable = Runnable {
        overlayTitle?.let { t ->
            val a = ObjectAnimator.ofFloat(t, "alpha", 0.85f, 1f).apply {
                duration = 1200L
                repeatMode = ObjectAnimator.REVERSE
                repeatCount = ObjectAnimator.INFINITE
            }
            activeAnimators += a
            a.start()
        }
        overlayName?.let { n ->
            val a = ObjectAnimator.ofFloat(n, "alpha", 0.85f, 1f).apply {
                duration = 1200L
                repeatMode = ObjectAnimator.REVERSE
                repeatCount = ObjectAnimator.INFINITE
            }
            activeAnimators += a
            a.start()
        }
    }
    private var overlayGlow: View? = null
    private var overlayRingOuter: View? = null
    private var overlayRingInner: View? = null
    private var overlayStarfield: StarfieldView? = null
    private var overlayRipple: RippleView? = null
    private var overlayAvatar: TextView? = null
    private var overlayTitle: TextView? = null
    private var overlayName: TextView? = null
    private var overlaySub: TextView? = null

    fun isShowing(): Boolean = root != null

    /** 顯示/更新 overlay。重複呼叫會原地更新內容與自動關閉計時。 */
    fun show(context: Context, mode: Mode, name: String, kind: String) {
        val appContext = context.applicationContext
        if (!Settings.canDrawOverlays(appContext)) return
        if (root == null) {
            val wm = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val v = buildRoot(appContext)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP   // v3.11：y=0 頂部對齊，下滑拖動用 params.y 語意單純
            }
            try {
                wm.addView(v, params)
            } catch (e: Exception) {
                // 授權不足／系統拒絕 → 靜默失敗（震動仍是主要提醒）
                return
            }
            root = v
            overlayWm = wm
            overlayParams = params
        }
        updateContent(appContext, mode, name, kind)
        currentMode = mode
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        if (mode == Mode.MISSED || mode == Mode.DISCONNECTED) {
            // D15：螢幕持續亮 → 8s 自動消失；息屏由服務 dismiss
            val r = Runnable { dismiss() }
            dismissRunnable = r
            handler.postDelayed(r, AUTO_DISMISS_MS)
        } else if (mode == Mode.CALL) {
            // 螢幕可能關著：盡力喚醒（被系統拒絕也無妨，震動為主要提醒、抬腕可喚醒）
            try {
                val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
                // wakeUp 為隱藏 API：改用公開 WakeLock（ACQUIRE_CAUSES_WAKEUP，manifest 已有 WAKE_LOCK 權限）
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "linewatch:overlay"
                )
                wl.acquire(1500)
            } catch (e: Exception) {
            }
        }
        if (mode == Mode.CALL) startRingPulse() else stopRingPulse(staticAlpha = true)
        playEntrance()
    }

    fun dismiss() {
        cancelRingAnimators()
        dragAnimator?.cancel()
        dragAnimator = null
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        root?.let { v ->
            try {
                overlayWm?.removeView(v)
            } catch (_: Exception) {
            }
        }
        root = null
        contentContainer = null
        overlayWm = null
        overlayGlow = null
        overlayRingOuter = null
        overlayRingInner = null
        overlayStarfield = null
        overlayRipple = null
        overlayAvatar = null
        overlayTitle = null
        overlayName = null
        overlaySub = null
        overlayParams = null
    }

    // ---------- v3.11 拖動視窗 ----------

    private fun moveOverlayX(x: Float) {
        val p = overlayParams ?: return
        val v = root ?: return
        p.x = x.toInt()
        try {
            overlayWm?.updateViewLayout(v, p)
        } catch (_: Exception) {
        }
    }

    private fun springBackX() {
        val from = overlayParams?.x ?: return
        animateOverlayX(from, 0, 200L, onEnd = {
            // 彈回結束：移除 GPU 圖層＋CALL 態恢復星空/漣漪
            root?.setLayerType(View.LAYER_TYPE_NONE, null)
            if (currentMode == Mode.CALL) {
                overlayStarfield?.startStars()
                overlayRipple?.startRipples()
            }
        })
    }

    private fun flyOutOverlayX() {
        val from = overlayParams?.x ?: 0
        val target = android.content.res.Resources.getSystem().displayMetrics.widthPixels
        animateOverlayX(from, target, 180L, onEnd = {
            Protocol.logEvent("{\"t\":\"swipe_dismiss\",\"src\":\"overlay\"}")
            dismiss()
            dismissListener?.invoke()
        })
    }

    private fun animateOverlayX(from: Int, to: Int, duration: Long, onEnd: (() -> Unit)?) {
        dragAnimator?.cancel()
        dragAnimator = ValueAnimator.ofInt(from, to).apply {
            this.duration = duration
            addUpdateListener { a ->
                val p = overlayParams ?: return@addUpdateListener
                val v = root ?: return@addUpdateListener
                p.x = a.animatedValue as Int
                try {
                    overlayWm?.updateViewLayout(v, p)
                } catch (_: Exception) {
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    dragAnimator = null
                    onEnd?.invoke()
                }
            })
            start()
        }
    }

    // ---------- 內部 ----------

    private fun buildRoot(context: Context): FrameLayout {
        val frame = FrameLayout(context).apply {
            setBackgroundResource(R.drawable.bg_round_black)   // v3.15：圓形黑卡（角落透明）
            clipChildren = false
            clipToPadding = false
        }
        // v3.11：拖動視窗——MOVE 以 updateViewLayout 跟手移動 params.y；UP ≥80px 飛出、否則回彈
        frame.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX = ev.rawX
                    dragStartRawY = ev.rawY
                    dragging = true
                    lastOverlayX = Float.MAX_VALUE
                    lastMoveMs = 0L
                    // v3.13：GPU 紋理快取＋暫停星空/漣漪
                    v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                    overlayStarfield?.stopStars()
                    overlayRipple?.stopRipples()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) return@setOnTouchListener true
                    if (ev.pointerCount > 1) {
                        dragging = false
                        springBackX()
                        return@setOnTouchListener true
                    }
                    val dx = (ev.rawX - dragStartRawX).coerceAtLeast(0f)
                    val damped = if (dx > 160f) 160f + (dx - 160f) * 0.6f else dx
                    // 節流：避免每事件 IPC（dx 變化 ≥8px 或 ≥16ms 才 updateViewLayout）
                    val now = android.os.SystemClock.uptimeMillis()
                    if (kotlin.math.abs(damped - lastOverlayX) >= 8f || now - lastMoveMs >= 16L) {
                        moveOverlayX(damped)
                        lastOverlayX = damped
                        lastMoveMs = now
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) return@setOnTouchListener true
                    dragging = false
                    val dx = (ev.rawX - dragStartRawX).coerceAtLeast(0f)
                    val dy = ev.rawY - dragStartRawY
                    val verticalInterference = kotlin.math.abs(dy) > dx * 0.5f
                    if (!verticalInterference && dx >= 80f) {
                        flyOutOverlayX()
                    } else {
                        springBackX()
                    }
                    true
                }
                else -> true
            }
        }
        val starfield = StarfieldView(context)
        frame.addView(starfield, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        overlayStarfield = starfield
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL   // v3.6：頂部對齊幾何布局（不用 CENTER 溢出模式）
            setPadding(0, dp(28), 0, 0)          // v3.9：光環頂＝28dp（頭像圓心 62dp＝屏高 26.6%）
            clipChildren = false                 // 漣漪祖先容器放行（方形裁剪根因修復）
            clipToPadding = false
        }
        frame.addView(content, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ))
        contentContainer = content
        return frame
    }

    private fun updateContent(context: Context, mode: Mode, name: String, kind: String) {
        val v = contentContainer ?: return
        v.removeAllViews()

        val accent = when (mode) {
            Mode.CALL -> COLOR_LINE_GREEN
            Mode.MISSED -> COLOR_ALERT
            Mode.DISCONNECTED -> COLOR_TEXT_SECONDARY
        }
        val titleColor = when (mode) {
            Mode.CALL -> COLOR_TEXT_PRIMARY
            Mode.MISSED -> COLOR_ALERT
            Mode.DISCONNECTED -> COLOR_TEXT_SECONDARY
        }
        val titleText = when (mode) {
            Mode.CALL ->
                if (kind == "video") context.getString(R.string.title_incoming_video)
                else context.getString(R.string.title_incoming)
            Mode.MISSED -> context.getString(R.string.title_missed)
            Mode.DISCONNECTED -> context.getString(R.string.title_disconnected)
        }
        val subColor = when (mode) {
            Mode.CALL -> COLOR_LINE_GREEN
            else -> COLOR_TEXT_SECONDARY
        }
        val subText = when (mode) {
            Mode.CALL -> context.getString(R.string.subtitle_vibrating)
            Mode.MISSED -> context.getString(R.string.subtitle_missed)
            Mode.DISCONNECTED -> context.getString(R.string.subtitle_disconnected)
        }

        // 頭像＋漣漪＋雙層光圈＋發光（與 IncomingCallActivity v3.3 同構：漣漪為容器首子層 → 結構同心）
        val ringContainer = FrameLayout(context).apply {
            clipChildren = false
            clipToPadding = false
        }
        val ripple = RippleView(context)
        ringContainer.addView(ripple, FrameLayout.LayoutParams(dp(160), dp(160), Gravity.CENTER))
        overlayRipple = ripple
        val glow = View(context)
        glow.alpha = 0.2f
        glow.background = context.getDrawable(R.drawable.bg_glow)?.apply { setTint(accent) }
        ringContainer.addView(glow, FrameLayout.LayoutParams(dp(68), dp(68), Gravity.CENTER))
        val ringOuter = View(context)
        ringOuter.alpha = 0.15f
        ringOuter.background = context.getDrawable(R.drawable.bg_ring)?.apply { setTint(accent) }
        ringContainer.addView(ringOuter, FrameLayout.LayoutParams(dp(68), dp(68), Gravity.CENTER))
        val ringInner = View(context)
        ringInner.alpha = 0.55f
        ringInner.background = context.getDrawable(R.drawable.bg_ring_inner)?.apply { setTint(accent) }
        ringContainer.addView(ringInner, FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER))
        val avatar = TextView(context).apply {
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
            gravity = Gravity.CENTER
            includeFontPadding = false
        }
        // ui-spec v3 快取優先：來電態查記憶體、未接態依名字查磁碟快取；v3.2 斷線態用專屬圖示
        val cached = when (mode) {
            Mode.CALL -> AvatarStore.bitmap
            Mode.DISCONNECTED -> null
            else -> AvatarStore.cachedBitmap(name)
        }
        if (cached != null) {
            avatar.text = ""
            avatar.background = AvatarStore.circularDrawable(context.resources, cached)
            avatar.scaleX = 0.9f
            avatar.scaleY = 0.9f
            avatar.alpha = 0.6f
            avatar.animate().scaleX(1f).scaleY(1f).setDuration(300L).start()
            avatar.animate().alpha(1f).setDuration(300L).start()
        } else if (mode == Mode.DISCONNECTED) {
            // v3.2：斷線態顯示二次元斷線圖示（不顯示頭像/首字）
            avatar.text = ""
            avatar.background = context.getDrawable(R.drawable.ic_disconnect_kawaii)
            avatar.alpha = 1f
            avatar.scaleX = 1f
            avatar.scaleY = 1f
        } else {
            if (mode == Mode.DISCONNECTED) {
                // v3.2：斷線態專屬圖示（不顯示首字，ui-spec v3.2）
                avatar.text = ""
                avatar.background = context.getDrawable(R.drawable.ic_disconnect_kawaii)
            } else {
                avatar.text = firstCharOf(name)
                avatar.background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(accent)
                }
            }
            // 重置：避免 cross-fade 動畫中途切態殘留半透明 alpha
            avatar.alpha = 1f
            avatar.scaleX = 1f
            avatar.scaleY = 1f
        }
        ringContainer.addView(avatar, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER))
        v.addView(ringContainer, LinearLayout.LayoutParams(dp(68), dp(68)))
        overlayGlow = glow
        overlayRingOuter = ringOuter
        overlayRingInner = ringInner
        overlayAvatar = avatar

        // v3.5：DISCONNECTED 完全關閉來電特效（僅圖示＋標題＋副標）；CALL/MISSED 恢復可見
        val effectsVisible = mode != Mode.DISCONNECTED
        glow.visibility = if (effectsVisible) View.VISIBLE else View.GONE
        ringOuter.visibility = if (effectsVisible) View.VISIBLE else View.GONE
        ringInner.visibility = if (effectsVisible) View.VISIBLE else View.GONE
        ripple.visibility = if (effectsVisible) View.VISIBLE else View.GONE
        overlayStarfield?.visibility = if (effectsVisible) View.VISIBLE else View.GONE

        // 標題
        val title = TextView(context).apply {
            text = titleText
            textSize = 20f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(titleColor)
            includeFontPadding = false
        }
        v.addView(title, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })
        overlayTitle = title

        // 名字 40sp、自動縮放至 70% 寬（match_parent＋15% padding 約束，密度自適應）
        val nameView = TextView(context).apply {
            text = name
            textSize = 40f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(COLOR_TEXT_PRIMARY)
            maxLines = 1
            gravity = Gravity.CENTER
            includeFontPadding = false
            setAutoSizeTextTypeUniformWithConfiguration(12, 34, 1, TypedValue.COMPLEX_UNIT_SP)
        }
        val namePad = (context.resources.displayMetrics.widthPixels * 0.15f).toInt()
        nameView.setPadding(namePad, 0, namePad, 0)
        v.addView(nameView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        overlayName = nameView
        // D16：Yomogi 手寫字體（首字/標題/名字；副標在下方聲明後套用）

        val sub = TextView(context).apply {
            text = subText
            textSize = 12f
            setTextColor(subColor)
            includeFontPadding = false
        }
        v.addView(sub, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        overlaySub = sub
        Fonts.applyYomogi(context, avatar, title, nameView, sub)
    }

    /** 名字首字（處理 emoji 代理對；空白 → 「?」）。 */
    private fun firstCharOf(name: String): String {
        if (name.isBlank()) return "?"
        val cp = name.codePointAt(0)
        return String(Character.toChars(cp))
    }

    /** v3 特效（僅來電態）：雙層光圈反向相位＋發光＋背景 radial＋文字微光（延遲啟動）。 */
    private fun startRingPulse() {
        cancelRingAnimators()
        fun pulse(view: View?, from: Float, to: Float, duration: Long = 1200L) {
            view ?: return
            view.alpha = from
            val a = ObjectAnimator.ofFloat(view, "alpha", from, to).apply {
                this.duration = duration
                repeatMode = ObjectAnimator.REVERSE
                repeatCount = ObjectAnimator.INFINITE
            }
            activeAnimators += a
            a.start()
        }
        pulse(overlayRingOuter, 0.15f, 0.55f)
        pulse(overlayRingInner, 0.55f, 0.15f)
        pulse(overlayGlow, 0.2f, 0.6f)
        overlayStarfield?.startStars()
        overlayRipple?.startRipples()
        handler.removeCallbacks(textPulseRunnable)
        handler.postDelayed(textPulseRunnable, 350L)
    }

    private fun stopRingPulse(staticAlpha: Boolean) {
        cancelRingAnimators()
        if (staticAlpha) {
            overlayRingOuter?.alpha = 0.25f
            overlayRingInner?.alpha = 0.25f
            overlayGlow?.alpha = 0.2f
            overlayTitle?.alpha = 1f
            overlayName?.alpha = 1f
        }
        overlayStarfield?.stopStars()
        overlayRipple?.stopRipples()
    }

    private fun cancelRingAnimators() {
        handler.removeCallbacks(textPulseRunnable)
        activeAnimators.forEach { it.cancel() }
        activeAnimators.clear()
    }

    /** 進場：頭像＋名字 scale 0.85→1.0；標題/副標淡入。 */
    private fun playEntrance() {
        val avatar = overlayAvatar ?: return
        val nameView = overlayName
        val title = overlayTitle
        val sub = overlaySub
        avatar.scaleX = 0.85f
        avatar.scaleY = 0.85f
        avatar.animate().scaleX(1f).scaleY(1f).setDuration(240L).start()
        nameView?.let {
            it.scaleX = 0.85f
            it.scaleY = 0.85f
            it.animate().scaleX(1f).scaleY(1f).setDuration(240L).start()
        }
        title?.let {
            it.alpha = 0f
            it.animate().alpha(1f).setDuration(300L).start()
        }
        sub?.let {
            it.alpha = 0f
            it.animate().alpha(1f).setDuration(300L).start()
        }
    }

    private fun dp(value: Int): Int =
        (value * android.content.res.Resources.getSystem().displayMetrics.density).toInt()
}
