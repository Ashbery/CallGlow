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
import android.widget.Switch
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
    private lateinit var patternGroup: RadioGroup
    private val vibratorController by lazy { VibratorController(applicationContext) }
    private var dragStartRawX = 0f
    private var dragStartRawY = 0f
    private var dragging = false

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
        setupPatternGroup()
        setupVibModeGroup()
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
        patternGroup = findViewById(R.id.pattern_group)
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

    private fun setupPatternGroup() {
        val rapid = findViewById<RadioButton>(R.id.pattern_rapid)
        val medium = findViewById<RadioButton>(R.id.pattern_medium)
        val long = findViewById<RadioButton>(R.id.pattern_long)
        when (Prefs.getPatternKey(this)) {
            Prefs.PATTERN_RAPID -> rapid.isChecked = true
            Prefs.PATTERN_LONG -> long.isChecked = true
            else -> medium.isChecked = true
        }
        patternGroup.setOnCheckedChangeListener { _, checkedId ->
            val key = when (checkedId) {
                R.id.pattern_rapid -> Prefs.PATTERN_RAPID
                R.id.pattern_long -> Prefs.PATTERN_LONG
                else -> Prefs.PATTERN_MEDIUM
            }
            Prefs.setPatternKey(this, key)
            Protocol.logEvent(
                "{\"t\":\"settings\",\"key\":\"vib_pattern\",\"value\":\"$key\"}"
            )
            vibratorController.previewOnce()   // v3.17：點選即預覽（CALLING 中自動跳過）
        }
    }

    private fun setupVibModeGroup() {
        val sw = findViewById<Switch>(R.id.switch_system_effect)
        val group = findViewById<RadioGroup>(R.id.vib_mode_group)
        val click = findViewById<RadioButton>(R.id.mode_click)
        val doubleClick = findViewById<RadioButton>(R.id.mode_double_click)
        val tick = findViewById<RadioButton>(R.id.mode_tick)
        val heavy = findViewById<RadioButton>(R.id.mode_heavy_click)

        sw.isChecked = Prefs.getUseSystemEffect(this)
        when (Prefs.getVibMode(this)) {
            Prefs.MODE_DOUBLE_CLICK -> doubleClick.isChecked = true
            Prefs.MODE_TICK -> tick.isChecked = true
            Prefs.MODE_HEAVY_CLICK -> heavy.isChecked = true
            else -> click.isChecked = true   // 預設短按
        }
        group.visibility = if (sw.isChecked) View.VISIBLE else View.GONE

        sw.setOnCheckedChangeListener { _, checked ->
            Prefs.setUseSystemEffect(this, checked)
            group.visibility = if (checked) View.VISIBLE else View.GONE
            Protocol.logEvent(
                "{\"t\":\"settings\",\"key\":\"use_system_effect\",\"value\":$checked}"
            )
        }

        group.setOnCheckedChangeListener { _, checkedId ->
            val value = when (checkedId) {
                R.id.mode_double_click -> Prefs.MODE_DOUBLE_CLICK
                R.id.mode_tick -> Prefs.MODE_TICK
                R.id.mode_heavy_click -> Prefs.MODE_HEAVY_CLICK
                else -> Prefs.MODE_CLICK
            }
            Prefs.setVibMode(this, value)
            Protocol.logEvent(
                "{\"t\":\"settings\",\"key\":\"vib_mode\",\"value\":\"$value\"}"
            )
            vibratorController.previewOnce()   // v3.17：點選即預覽
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

    // ---------- v3.18 右滑關閉（與來電畫面一致；不影響垂直滾動） ----------

    private fun setupSwipeDismiss() {
        val root = findViewById<View>(R.id.settings_scroll)
        root.setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragStartRawX = ev.rawX
                    dragStartRawY = ev.rawY
                    dragging = false
                    false   // 不攔截 DOWN：ScrollView 垂直滾動正常
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!dragging) {
                        val dx = ev.rawX - dragStartRawX
                        val dy = ev.rawY - dragStartRawY
                        // 橫拖判定：dx>32px 且明顯水平 → 開始攔截（垂直滾動優先交給 ScrollView）
                        if (dx > 32f && dx > kotlin.math.abs(dy) * 1.2f) {
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
                    val dx = (ev.rawX - dragStartRawX).coerceAtLeast(0f)
                    val dy = ev.rawY - dragStartRawY
                    val verticalInterference = kotlin.math.abs(dy) > dx * 0.5f
                    if (!verticalInterference && dx >= 80f) {
                        Protocol.logEvent("{\"t\":\"swipe_dismiss\",\"src\":\"settings\"}")
                        finish()   // 系統右滑關閉過場自然接續
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
