package com.linewatch.watch

import android.animation.ObjectAnimator
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.TextView

/**
 * 來電／未接／斷線提示畫面（docs/ui-spec.md 手錶端章節；同一 Activity 狀態切換）。
 * - 全螢幕黑底＋KEEP_SCREEN_ON（finish 自動解除）
 * - 來電：首字頭像＋脈動光圈＋「LINE 來電／LINE 視訊來電」20sp＋名字 40sp 自動縮放（70% 寬）＋「● 震動提醒中」
 * - 未接：標題「LINE 未接來電」警示色 #FF8A65＋「對方可能已掛斷」＋8s 自動 finish
 * - 斷線：「藍牙已斷線」
 * - v2 視覺（roadmap V2-2/V2-3/V2-4-v1）：光圈 alpha 呼吸（僅來電態）、進場縮放/淡入（ObjectAnimator 級）
 * - debug 深連結（T3/T4 測試）：
 *   adb shell am start -n com.linewatch.watch/.IncomingCallActivity --es name 測試 --ez missed false
 * - debug 停止（ColorOS 擋 shell 廣播 → 改深連結 extra 轉交 service）：
 *   adb shell am start -n com.linewatch.watch/.IncomingCallActivity --ez debug_end true --ez missed false
 */
class IncomingCallActivity : Activity() {

    companion object {
        const val EXTRA_NAME = "name"
        const val EXTRA_KIND = "kind"
        const val EXTRA_MISSED = "missed"
        const val EXTRA_FROM_SERVICE = "fromService"
        const val EXTRA_DISCONNECTED = "disconnected"
        // ColorOS 擋 shell 廣播（DEBUG_END broadcast 被拒）→ 以深連結 extra 轉交 service endCall
        const val EXTRA_DEBUG_END = "debug_end"

        /** Service 判斷 Activity 是否在前台（決定 broadcast 或 startActivity）。 */
        @Volatile
        var isVisible = false
            private set
    }

    private lateinit var ringOuterView: View
    private lateinit var ringInnerView: View
    private lateinit var glowView: View
    private lateinit var rippleView: RippleView
    private lateinit var starfieldBgView: StarfieldView
    private lateinit var auroraView: AuroraView      // D17 銀河極光
    private lateinit var edgeHaloView: EdgeHaloView  // D17 邊緣呼吸光環
    private lateinit var avatarView: TextView
    private lateinit var titleView: TextView
    private lateinit var nameView: TextView
    private lateinit var subtitleView: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val vibratorController by lazy { VibratorController(applicationContext) }

    private var callerName: String = ""
    private var kind: String = "voice"
    private val activeAnimators = mutableListOf<ObjectAnimator>()
    private val textPulseRunnable = Runnable {
        // 進場淡入（300ms）結束後才啟動文字微光，避免動畫打架
        val t = ObjectAnimator.ofFloat(titleView, "alpha", 0.85f, 1f).apply {
            duration = 1200L
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
        }
        val n = ObjectAnimator.ofFloat(nameView, "alpha", 0.85f, 1f).apply {
            duration = 1200L
            repeatMode = ObjectAnimator.REVERSE
            repeatCount = ObjectAnimator.INFINITE
        }
        activeAnimators += t
        activeAnimators += n
        t.start()
        n.start()
    }
    private var uiState: String = Protocol.STATE_CALL
    private var avatarTint: Int = 0


    // D17.10：副標柔光呼吸（文字恆定，只有 shadow 光暈隨節拍擴散/收縮）
    private var subGlowRadius = 0f
    private var subGlowAnimator: android.animation.ValueAnimator? = null
    private fun subGlowTo(target: Float, duration: Long) {
        subGlowAnimator?.cancel()
        subGlowAnimator = android.animation.ValueAnimator.ofFloat(subGlowRadius, target).apply {
            this.duration = duration
            addUpdateListener { a ->
                subGlowRadius = a.animatedValue as Float
                subtitleView.setShadowLayer(subGlowRadius, 0f, 0f, getColor(R.color.galaxy_cyan))
            }
            start()
        }
    }

    // D17 節拍明滅（與震動同步）：亮相 → 停頓 → 循環
    private var beatOnMs = 600L
    private var beatOffMs = 400L
    private val beatBright: Runnable = object : Runnable {
        override fun run() {
            if (uiState != Protocol.STATE_CALL) return
            ringOuterView.animate().alpha(0.55f).setDuration(140L).start()
            ringInnerView.animate().alpha(0.15f).setDuration(140L).start()
            glowView.animate().alpha(0.6f).setDuration(140L).start()
            // D17.10：副標柔光呼吸（光暈擴散）
            subGlowTo(6f * resources.displayMetrics.density, 240L)
            handler.postDelayed(beatDim, beatOnMs)
        }
    }
    private val beatDim: Runnable = object : Runnable {
        override fun run() {
            if (uiState != Protocol.STATE_CALL) return
            ringOuterView.animate().alpha(0.15f).setDuration(200L).start()
            ringInnerView.animate().alpha(0.55f).setDuration(200L).start()
            glowView.animate().alpha(0.2f).setDuration(200L).start()
            subGlowTo(2f * resources.displayMetrics.density, 320L)
            handler.postDelayed(beatBright, beatOffMs)
        }
    }

    private val finishRunnable = Runnable { finish() }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Protocol.ACTION_HIDE_UI) {
                // D15：息屏後隱藏來電畫面（不結束通話；震動由 service 繼續負責，直到接通/掛斷）
                Protocol.logEvent("{\"t\":\"hide_ui\",\"src\":\"screen_off\"}")
                finish()
                return
            }
            if (intent.action == Protocol.ACTION_AVATAR) {
                // T8：頭像到達 → 來電中才重新渲染
                if (uiState == Protocol.STATE_CALL) renderAvatar()
                return
            }
            if (intent.action != Protocol.ACTION_STATE) return
            when (intent.getStringExtra(Protocol.EXTRA_STATE)) {
                Protocol.STATE_CALL -> {
                    callerName = intent.getStringExtra(EXTRA_NAME) ?: callerName
                    kind = intent.getStringExtra(EXTRA_KIND) ?: kind
                    showCall()
                }
                Protocol.STATE_MISSED -> {
                    callerName = intent.getStringExtra(EXTRA_NAME) ?: callerName
                    showMissed()
                }
                Protocol.STATE_END -> finish()
                Protocol.STATE_DISCONNECTED -> showDisconnected()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        callerName = getString(R.string.unknown_caller)
        setContentView(R.layout.activity_incoming_call)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        hideSystemBars()
        bindViews()
        configureNameAutosize()
        // D15：receiver 註冊於 onCreate（息屏時 onStop 先跑 → 舊寫法收不到 HIDE_UI）
        val filter = IntentFilter(Protocol.ACTION_STATE)
        filter.addAction(Protocol.ACTION_AVATAR)
        filter.addAction(Protocol.ACTION_HIDE_UI)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, filter)
        }
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        isVisible = true
        // Activity 可見 → overlay 備援不再需要（BAL 受限時 overlay 先顯示，亮屏後由此交還 Activity）
        OverlayHelper.dismiss()
    }

    override fun onStop() {
        super.onStop()
        isVisible = false
    }

    override fun onDestroy() {
        handler.removeCallbacks(finishRunnable)
        cancelRingPulse()
        try {
            unregisterReceiver(stateReceiver)
        } catch (_: IllegalArgumentException) {
        }
        super.onDestroy()
    }

    // ---------- UI ----------

    private fun bindViews() {
        ringOuterView = findViewById(R.id.ring_outer)
        ringInnerView = findViewById(R.id.ring_inner)
        glowView = findViewById(R.id.glow)
        rippleView = findViewById(R.id.ripple)
        starfieldBgView = findViewById(R.id.starfield_bg)
        auroraView = findViewById(R.id.aurora)
        edgeHaloView = findViewById(R.id.edge_halo)
        avatarView = findViewById(R.id.avatar)
        titleView = findViewById(R.id.title)
        nameView = findViewById(R.id.name)
        subtitleView = findViewById(R.id.subtitle)
        // D16：所有通知文字統一 Yomogi 手寫字體（標題/名字/副標/首字頭像）
        Fonts.applyYomogi(this, avatarView, titleView, nameView, subtitleView)
    }

    /** 名字最大 28sp（D16 二修：長英文名不出界）、自動縮放至螢幕寬 70% 內；
     *  寬度約束：match_parent＋左右各 15% padding → 可用寬 = 70% 屏寬（密度自適應）；
     *  不用 wrap_content＋maxWidth（該組合的 autosize 量測有已知怪癖）。 */
    private fun configureNameAutosize() {
        val pad = (resources.displayMetrics.widthPixels * 0.15f).toInt()
        nameView.setPadding(pad, 0, pad, 0)
        nameView.setAutoSizeTextTypeUniformWithConfiguration(12, 28, 1, TypedValue.COMPLEX_UNIT_SP)
    }

    /** D17：名字太長（12sp 仍超出屏寬 70%）→ 固定 16sp＋marquee 橫向滾動；否則維持 autosize。 */
    private fun applyNameDisplay(name: String) {
        val avail = resources.displayMetrics.widthPixels * 0.70f
        val test = android.text.TextPaint().apply {
            textSize = 12f * resources.displayMetrics.scaledDensity
            typeface = Fonts.yomogi(this@IncomingCallActivity)
        }
        if (test.measureText(name) > avail) {
            // 固定 16sp＋marquee（autosize 縮放會與 ellipsize 衝突 → 先關閉 autosize）
            nameView.setAutoSizeTextTypeWithDefaults(android.widget.TextView.AUTO_SIZE_TEXT_TYPE_NONE)
            nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            nameView.ellipsize = android.text.TextUtils.TruncateAt.MARQUEE
            nameView.marqueeRepeatLimit = -1
            nameView.isSelected = true
        } else {
            nameView.setAutoSizeTextTypeUniformWithConfiguration(12, 28, 1, TypedValue.COMPLEX_UNIT_SP)
            nameView.ellipsize = null
            nameView.isSelected = false
        }
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val fromService = intent.getBooleanExtra(EXTRA_FROM_SERVICE, false)
        val missed = intent.getBooleanExtra(EXTRA_MISSED, false)
        intent.getStringExtra(EXTRA_NAME)?.takeIf { it.isNotBlank() }?.let { callerName = it }
        intent.getStringExtra(EXTRA_KIND)?.takeIf { it.isNotBlank() }?.let { kind = it }
        if (intent.getBooleanExtra(EXTRA_DEBUG_END, false)) {
            // debug 測試手段（ColorOS 擋 am broadcast）：Activity 在前台，轉交 service 走 endCall
            startService(
                Intent(this, BlePeripheralService::class.java)
                    .setAction(Protocol.ACTION_DEBUG_END)
                    .putExtra("missed", missed)
            )
            if (missed) {
                // 直接顯示未接畫面（8s 自動關）；震動由 service 統一負責
                showMissed()
            } else {
                finish()
            }
            return
        }
        when {
            intent.getBooleanExtra(EXTRA_DISCONNECTED, false) -> showDisconnected()
            missed -> {
                showMissed()
                // debug 深連結未接：單次 [300,200,300]；service 路徑不重啟震動
                if (!fromService) vibratorController.startMissed()
            }
            else -> {
                showCall()
                if (!fromService) {
                    // debug 深連結來電：轉交 BlePeripheralService 統一管理
                    // （震動＋120s watchdog；停止用 --ez debug_end true 深連結，ColorOS 擋 am broadcast）
                    startService(
                        Intent(this, BlePeripheralService::class.java)
                            .setAction(Protocol.ACTION_DEBUG_START)
                            .putExtra(EXTRA_NAME, callerName)
                            .putExtra(EXTRA_KIND, kind)
                    )
                }
            }
        }
    }

    private fun showCall() {
        uiState = Protocol.STATE_CALL
        // D15：call 態恢復亮屏保持（missed/disconnected 已清除）
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.removeCallbacks(finishRunnable)
        // 特效層恢復可見（自 DISCONNECTED 轉回）
        ringOuterView.visibility = View.VISIBLE
        ringInnerView.visibility = View.VISIBLE
        glowView.visibility = View.VISIBLE
        starfieldBgView.visibility = View.VISIBLE
        auroraView.visibility = View.VISIBLE
        edgeHaloView.visibility = View.VISIBLE
        titleView.setText(if (kind == "video") R.string.title_incoming_video else R.string.title_incoming)
        titleView.setTextColor(getColor(R.color.line_green))   // D17.8：LINE 品牌綠，與白色名字區分
        tintAvatar(getColor(R.color.line_green))   // D17：CALLING 走銀河 drawable（見 tintAvatar）
        nameView.text = callerName
        nameView.setTextColor(getColor(R.color.text_primary))
        applyNameDisplay(callerName)
        subtitleView.setText(R.string.subtitle_vibrating)
        subtitleView.setTextColor(getColor(R.color.galaxy_cyan))  // D17.8：星雲青副標（與標題綠區分）
        startRingPulse()
        playEntrance()
    }

    private fun showMissed() {
        uiState = Protocol.STATE_MISSED
        handler.removeCallbacks(finishRunnable)
        // 特效層恢復可見（靜態低 alpha；自 DISCONNECTED 轉回）
        ringOuterView.visibility = View.VISIBLE
        ringInnerView.visibility = View.VISIBLE
        glowView.visibility = View.VISIBLE
        starfieldBgView.visibility = View.VISIBLE
        titleView.setText(R.string.title_missed)
        titleView.setTextColor(getColor(R.color.alert))
        tintAvatar(getColor(R.color.alert))
        nameView.text = callerName
        nameView.setTextColor(getColor(R.color.text_primary))
        applyNameDisplay(callerName)
        subtitleView.setText(R.string.subtitle_missed)
        subtitleView.setTextColor(getColor(R.color.text_secondary))
        // D15：螢幕持續亮 → 8s 自動關（右滑可提前關）；息屏 → 服務發 HIDE_UI 結束、抬腕不再顯示
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.postDelayed(finishRunnable, Protocol.MISSED_AUTO_FINISH_MS)
        stopGalaxyEffects()
        stopRingPulse(staticAlpha = true)
        playEntrance()
    }

    private fun showDisconnected() {
        uiState = Protocol.STATE_DISCONNECTED
        handler.removeCallbacks(finishRunnable)
        titleView.setText(R.string.title_disconnected)
        titleView.setTextColor(getColor(R.color.text_secondary))
        renderAvatar()   // v3.2：斷線態顯示 kawaii 斷線圖示（不 tint，保留原色）
        nameView.text = ""
        subtitleView.setText(R.string.subtitle_disconnected)
        subtitleView.setTextColor(getColor(R.color.text_secondary))
        // D15：螢幕持續亮 → 8s 自動關（右滑可提前關）；息屏 → HIDE_UI
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handler.postDelayed(finishRunnable, Protocol.MISSED_AUTO_FINISH_MS)
        stopGalaxyEffects()
        stopRingPulse(staticAlpha = true)
        // v3.5：斷線態完全關閉來電特效（無動畫、無靜態殘留），只顯示圖示＋標題＋副標
        ringOuterView.visibility = View.GONE
        ringInnerView.visibility = View.GONE
        glowView.visibility = View.GONE
        starfieldBgView.visibility = View.GONE
        playEntrance()
    }

    // ---------- v3 視覺（ui-spec v3：雙層光圈＋發光＋背景 radial＋文字微光） ----------

    /** 頭像底／雙層光圈／發光依狀態 tint，並渲染頭像（T8 快取頭像優先、否則首字）。
     *  D17：CALLING 態改用銀河 sweep/radial drawable（不 tint），missed/disconnected 維持純色 tint。 */
    private fun tintAvatar(color: Int) {
        avatarTint = color
        if (uiState == Protocol.STATE_CALL) {
            ringOuterView.background = getDrawable(R.drawable.bg_ring_galaxy)
            ringInnerView.background = getDrawable(R.drawable.bg_ring_galaxy)
            glowView.background = getDrawable(R.drawable.bg_glow_galaxy)
        } else {
            ringOuterView.background = getDrawable(R.drawable.bg_ring)?.apply { setTint(color) }
            ringInnerView.background = getDrawable(R.drawable.bg_ring_inner)?.apply { setTint(color) }
            glowView.background = getDrawable(R.drawable.bg_glow)?.apply { setTint(color) }
        }
        renderAvatar()
    }

    /** 渲染頭像（ui-spec v3 快取優先）：來電態查記憶體、未接/斷線態依名字查磁碟快取；無則首字。 */
    private fun renderAvatar() {
        if (uiState == Protocol.STATE_DISCONNECTED) {
            // 斷線態：二次元斷線圖示，不顯示頭像（ui-spec v3.2，captain 提供 drawable）
            avatarView.text = ""
            avatarView.background = getDrawable(R.drawable.ic_disconnect_kawaii)
            avatarView.alpha = 1f
            avatarView.scaleX = 1f
            avatarView.scaleY = 1f
            return
        }
        val bmp = when {
            uiState == Protocol.STATE_CALL -> AvatarStore.bitmap
            else -> AvatarStore.cachedBitmap(callerName)
        }
        if (bmp != null) {
            avatarView.text = ""
            avatarView.background = AvatarStore.circularDrawable(resources, bmp)
            // 首字→真照片轉場：scale 0.9→1.0＋alpha 0.6→1.0（API 30 無 RenderEffect，cross-fade 代替模糊）
            avatarView.scaleX = 0.9f
            avatarView.scaleY = 0.9f
            avatarView.alpha = 0.6f
            avatarView.animate().scaleX(1f).scaleY(1f).setDuration(300L).start()
            avatarView.animate().alpha(1f).setDuration(300L).start()
        } else {
            avatarView.text = AvatarStore.firstCharOf(callerName)
            avatarView.background = if (uiState == Protocol.STATE_CALL) {
                getDrawable(R.drawable.bg_avatar_galaxy)   // D17：銀河首字底
            } else {
                getDrawable(R.drawable.bg_avatar)?.apply { setTint(avatarTint) }
            }
            // 重置：避免 cross-fade 動畫中途切態殘留半透明 alpha
            avatarView.alpha = 1f
            avatarView.scaleX = 1f
            avatarView.scaleY = 1f
        }
    }

    /** 特效全開（僅來電態，D17 銀河）：光圈隨震動節拍明滅＋30s 自轉、星空、銀河漣漪、極光、邊緣光環。 */
    private fun startRingPulse() {
        cancelRingPulse()
        // 節拍：自訂節奏讀 pattern [on,off]；系統效果模式 700ms 循環近似
        val useSystem = Prefs.getUseSystemEffect(this)
        val vibMode = Prefs.getVibMode(this)
        if (useSystem && vibMode.isNotEmpty()) {
            beatOnMs = 350L
            beatOffMs = 350L
        } else {
            val p = Prefs.callPatternFor(Prefs.getPatternKey(this))
            beatOnMs = p.getOrElse(0) { 600L }
            beatOffMs = p.getOrElse(1) { 400L }
        }
        ringOuterView.alpha = 0.15f
        ringInnerView.alpha = 0.55f
        glowView.alpha = 0.2f
        handler.removeCallbacks(beatBright)
        handler.removeCallbacks(beatDim)
        handler.post(beatBright)                                // 亮相 → 停頓相循環（與震動同步）
        // 銀河自轉：外環正轉、內環反轉（sweep 漸層旋轉 30s/圈）
        activeAnimators += ObjectAnimator.ofFloat(ringOuterView, "rotation", 0f, 360f).apply {
            duration = 30_000L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            start()
        }
        activeAnimators += ObjectAnimator.ofFloat(ringInnerView, "rotation", 0f, -360f).apply {
            duration = 30_000L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
            start()
        }
        starfieldBgView.startStars()                            // 星空主背景（僅來電態）
        rippleView.startRipples()                               // 銀河漣漪（僅來電態）
        auroraView.start()                                      // D17 極光（星雲緩流）
        edgeHaloView.start()                                    // D17 邊緣呼吸光環
        handler.removeCallbacks(textPulseRunnable)
        handler.postDelayed(textPulseRunnable, 350L)            // 文字微光（等進場淡入結束）
    }

    private fun stopRingPulse(staticAlpha: Boolean) {
        cancelRingPulse()
        if (staticAlpha) {
            // 未接/斷線態：全部靜止為低 alpha 常數
            ringOuterView.alpha = 0.25f
            ringInnerView.alpha = 0.25f
            glowView.alpha = 0.2f
            titleView.alpha = 1f
            nameView.alpha = 1f
            subtitleView.alpha = 1f
            subGlowAnimator?.cancel()
            subGlowAnimator = null
            subtitleView.setShadowLayer(0f, 0f, 0f, 0)
        }
        starfieldBgView.stopStars()   // 靜止為極淡靜態星空（alpha 0.15）
        rippleView.stopRipples()      // 漣漪停止（靜態背景）
    }

    private fun cancelRingPulse() {
        handler.removeCallbacks(textPulseRunnable)
        handler.removeCallbacks(beatBright)
        handler.removeCallbacks(beatDim)
        activeAnimators.forEach { it.cancel() }
        activeAnimators.clear()
    }

    /** D17：極光＋邊緣光環停止（missed/disconnected）。 */
    private fun stopGalaxyEffects() {
        auroraView.stop()
        edgeHaloView.stop()
        auroraView.visibility = View.GONE
        edgeHaloView.visibility = View.GONE
    }

    /** 進場：頭像＋名字 scale 0.85→1.0（240ms）；標題/副標淡入（300ms）。 */
    private fun playEntrance() {
        avatarView.scaleX = 0.85f
        avatarView.scaleY = 0.85f
        nameView.scaleX = 0.85f
        nameView.scaleY = 0.85f
        avatarView.animate().scaleX(1f).scaleY(1f).setDuration(240L).start()
        nameView.animate().scaleX(1f).scaleY(1f).setDuration(240L).start()
        titleView.alpha = 0f
        subtitleView.alpha = 0f
        titleView.animate().alpha(1f).setDuration(300L).start()
        subtitleView.animate().alpha(1f).setDuration(300L).start()
    }

    /**
     * v1.0.3b：系統返回（手勢/按鍵）在 CALLING 中忽略——避免 ColorOS Watch 的返回手勢
     * 誤關來電畫面；未接/斷線畫面維持正常返回。
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (uiState == Protocol.STATE_CALL) {
            Protocol.logEvent("{\"t\":\"back_ignored\",\"state\":\"call\"}")
            return
        }
        super.onBackPressed()
    }

    // ---------- v1.0.3e：自訂滑動偵測已移除 ----------
    // 系統 HeyTap 手勢（heytap_gesture_enable_app_list 白名單，install.bat 自動加入）負責返回；
    // CALLING 中由 onBackPressed 攔截保護（見上）；未接/斷線畫面由 8s 自動關或系統返回關閉。

    private fun hideSystemBars() {
        // v1.0.3e：只隱藏狀態列；導覽列保留（系統 HeyTap 返回手勢需要手勢區）
        window.insetsController?.apply {
            hide(WindowInsets.Type.statusBars())
        }
    }
}
