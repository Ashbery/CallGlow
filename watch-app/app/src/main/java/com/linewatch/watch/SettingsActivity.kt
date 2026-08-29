package com.linewatch.watch

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
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
    private lateinit var patternGroup: RadioGroup
    private val vibratorController by lazy { VibratorController(applicationContext) }

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
        setupPulsarGroup()
        setupButtons()
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

    /** v1.0.3：Pulsar 震動模式（16 選項＝自訂節奏＋15 種 Pulsar 預設；點選即預覽）。 */
    private fun setupPulsarGroup() {
        val group = findViewById<RadioGroup>(R.id.pulsar_group)
        val saved = Prefs.getPulsarPreset(this)
        val buttons = mutableMapOf<RadioButton, String>()

        val custom = RadioButton(this).apply {
            id = View.generateViewId()
            text = getString(R.string.settings_pulsar_custom)
            setTextColor(getColor(R.color.text_primary))
            textSize = 12f
        }
        group.addView(custom)
        buttons[custom] = ""
        if (saved.isEmpty()) custom.isChecked = true

        PulsarPresets.ITEMS.forEach { item ->
            val rb = RadioButton(this).apply {
                id = View.generateViewId()
                text = "${item.labelZh} ${item.labelEn}"
                setTextColor(getColor(R.color.text_primary))
                textSize = 12f
            }
            group.addView(rb)
            buttons[rb] = item.key
            if (saved == item.key) rb.isChecked = true
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

    // v1.0.3b：移除設定頁右滑關閉（ScrollView 滾動誤判為橫向關閉——實測 16:57:34 swipe_dismiss 即此誤觸）
    // 返回改由系統返回鍵/手勢負責；來電畫面仍保留嚴格橫向判定

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
