package com.linewatch.watch

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView

/**
 * v2 設定畫面（docs/ui-spec.md V2-1；MAIN/LAUNCHER → 手錶應用選單入口）。
 * 黑底、LINE 綠 accent、圓形安全區（內容置中）：
 * 1. 藍牙狀態列（已連線/待機中＋手錶名稱）
 * 2. 震動強度三檔（弱 100／中 150 預設／強 200）
 * 3. 震動節奏三檔（急促/適中/長震）
 * 4. 測試按鈕（測試來電/測試未接/停止，走既有 debug 路徑）
 * 5. 開機自啟說明
 */
class SettingsActivity : Activity() {

    private lateinit var btStatusView: TextView
    private lateinit var btNameView: TextView
    private lateinit var strengthGroup: RadioGroup
    private val vibratorController by lazy { VibratorController(applicationContext) }

    // v1.0.3i：左緣手勢區（110px ≈ 屏寬 24%；使用者實測自然返回手勢起手 x≤85）
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragging = false
    private var edgeArmed = false

    companion object {
        private const val EDGE_ZONE_PX = 110f
    }


    private val connReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Protocol.ACTION_CONN_STATE) {
                refreshBtStatus()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)
        bindViews()
        setupStrengthGroup()
        setupPulsarGroup()
        setupButtons()
        setupSwipeDismiss()
        refreshBtStatus()
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter(Protocol.ACTION_CONN_STATE)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(connReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(connReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try {
            unregisterReceiver(connReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    // ---------- 各區塊 ----------

    private fun bindViews() {
        btStatusView = findViewById(R.id.bt_status)
        btNameView = findViewById(R.id.bt_name)
        strengthGroup = findViewById(R.id.strength_group)
    }

    private fun setupStrengthGroup() {
        val weak = findViewById<RadioButton>(R.id.strength_weak)
        val medium = findViewById<RadioButton>(R.id.strength_medium)
        val strong = findViewById<RadioButton>(R.id.strength_strong)
        when (Prefs.getStrength(this)) {
            Prefs.STRENGTH_WEAK -> weak.isChecked = true
            Prefs.STRENGTH_STRONG -> strong.isChecked = true
            else -> medium.isChecked = true
        }
        strengthGroup.setOnCheckedChangeListener { _, checkedId ->
            val value = when (checkedId) {
                R.id.strength_weak -> Prefs.STRENGTH_WEAK
                R.id.strength_strong -> Prefs.STRENGTH_STRONG
                else -> Prefs.STRENGTH_MEDIUM
            }
            Prefs.setStrength(this, value)
            Protocol.logEvent(
                "{\"t\":\"settings\",\"key\":\"vib_strength\",\"value\":$value}"
            )
            vibratorController.previewOnce()   // v3.17：點選即預覽（CALLING 中自動跳過）
        }
    }

    /** v1.0.3d：Pulsar 震動模式（15 種；原「自訂節奏」與「震動節奏」列已移除——模式＋強度即足夠）。 */
    private fun setupPulsarGroup() {
        val group = findViewById<RadioGroup>(R.id.pulsar_group)
        // v1.0.3d：舊值/空值（自訂節奏）→ 自動改為預設 alarm
        val saved = Prefs.getPulsarPreset(this)
        val effective = if (PulsarPresets.ITEMS.any { it.key == saved }) saved else {
            Prefs.setPulsarPreset(this, "alarm")
            "alarm"
        }
        val buttons = mutableMapOf<RadioButton, String>()

        PulsarPresets.ITEMS.forEach { item ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = "${item.labelZh} ${item.labelEn}"
                setTextColor(getColor(R.color.text_primary))
                textSize = 12f
            }
            group.addView(rb)
            buttons[rb] = item.key
            if (effective == item.key) rb.isChecked = true
        }

        // 監聽器在所有選項建立後才掛（避免程式化設定 isChecked 觸發預覽）
        group.setOnCheckedChangeListener { _, _ ->
            val key = buttons.entries.firstOrNull { it.key.isChecked }?.value ?: ""
            Prefs.setPulsarPreset(this, key)
            Protocol.logEvent(
                "{\"t\":\"settings\",\"key\":\"vib_pulsar\",\"value\":\"$key\"}"
            )
            vibratorController.previewOnce()
        }
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btn_test_call).setOnClickListener {
            // 走既有 debug 深連結路徑：Activity 前台轉交 service（震動＋120s watchdog）
            Protocol.logEvent("{\"t\":\"settings\",\"action\":\"test_call\"}")
            startActivity(
                Intent(this, IncomingCallActivity::class.java)
                    .putExtra(IncomingCallActivity.EXTRA_NAME, "測試")
                    .putExtra(IncomingCallActivity.EXTRA_MISSED, false)
            )
        }
        findViewById<Button>(R.id.btn_test_missed).setOnClickListener {
            Protocol.logEvent("{\"t\":\"settings\",\"action\":\"test_missed\"}")
            startActivity(
                Intent(this, IncomingCallActivity::class.java)
                    .putExtra(IncomingCallActivity.EXTRA_NAME, "測試")
                    .putExtra(IncomingCallActivity.EXTRA_MISSED, true)
            )
        }
        findViewById<Button>(R.id.btn_test_stop).setOnClickListener {
            // 前台 Activity 直接轉交 service（等同 debug_end，免繞 Activity）
            Protocol.logEvent("{\"t\":\"settings\",\"action\":\"test_stop\"}")
            startService(
                Intent(this, BlePeripheralService::class.java)
                    .setAction(Protocol.ACTION_DEBUG_END)
                    .putExtra("missed", false)
            )
        }
    }

    // ---------- v1.0.3i 最終：左緣起手右滑返回 ----------
    // 診斷實測（19:06）：使用者自然返回手勢＝左緣（x≤110）起手往右滑；座標軸正常。
    // 規則：起手點須在左緣 110px 內 ＋ dx≥100 ＋ |dy|≤dx×0.4（容許 ±21°）；
    // 中間起手的一切滑動（含斜向）永不觸發；來電畫面完全不支援滑動關閉。

    private fun setupSwipeDismiss() {
        val root = findViewById<View>(R.id.settings_scroll)
        root.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX = ev.rawX
                    dragStartRawY = ev.rawY
                    edgeArmed = ev.rawX <= EDGE_ZONE_PX
                    dragging = false
                    false   // 不攔截 DOWN：ScrollView 垂直滾動正常
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) {
                        val dx = ev.rawX - dragStartRawX
                        val dy = ev.rawY - dragStartRawY
                        if (edgeArmed && dx >= 40f && kotlin.math.abs(dy) <= dx) {
                            dragging = true
                            v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
                        } else {
                            return@setOnTouchListener false
                        }
                    }
                    val dx = (ev.rawX - dragStartRawX).coerceAtLeast(0f)
                    v.translationX = dampDrag(dx)
                    v.alpha = 1f - (dx.coerceAtMost(160f) / 160f) * 0.75f
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!dragging) return@setOnTouchListener false
                    dragging = false
                    val dx = ev.rawX - dragStartRawX
                    val dy = ev.rawY - dragStartRawY
                    if (edgeArmed && dx >= 100f && kotlin.math.abs(dy) <= dx * 0.4f) {
                        Protocol.logEvent(
                            "{\"t\":\"swipe_dismiss\",\"src\":\"settings\",\"dx\":${dx.toInt()},\"dy\":${dy.toInt()},\"startX\":${dragStartRawX.toInt()}}"
                        )
                        finish()
                    } else {
                        v.animate()
                            .translationX(0f)
                            .alpha(1f)
                            .setDuration(200L)
                            .setInterpolator(android.view.animation.DecelerateInterpolator())
                            .withEndAction { v.setLayerType(View.LAYER_TYPE_NONE, null) }
                            .start()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun dampDrag(dx: Float): Float =
        if (dx > 160f) 160f + (dx - 160f) * 0.6f else dx

    private fun refreshBtStatus() {
        if (BlePeripheralService.bleConnected) {
            btStatusView.setText(R.string.settings_bt_connected)
            btStatusView.setTextColor(getColor(R.color.line_green))
        } else {
            btStatusView.setText(R.string.settings_bt_idle)
            btStatusView.setTextColor(getColor(R.color.text_secondary))
        }
        val watchName = try {
            BluetoothAdapter.getDefaultAdapter()?.name
        } catch (e: SecurityException) {
            null
        }
        btNameView.text = getString(R.string.settings_bt_name, watchName ?: "手錶")
    }
}
