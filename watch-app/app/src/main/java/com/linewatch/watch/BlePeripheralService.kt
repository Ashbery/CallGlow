package com.linewatch.watch

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.graphics.BitmapFactory
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * BLE Peripheral 前台服務（docs/architecture.md 手錶端元件 1）：
 * - openGattServer（Nordic UART Service）＋ advertise LOW_LATENCY
 * - CHAR_CMD 寫入 → 狀態機（IDLE/CALLING）→ 啟動/關閉 IncomingCallActivity＋震動
 * - CHAR_STATE notify：ack / pong（心跳回應）
 * - 斷線：震動中 → 交由 120s watchdog 收尾；非震動中 → 顯示「藍牙已斷線」提示
 * - 執行緒：BLE 操作在 worker thread；狀態與 UI 回主執行緒（docs/architecture.md）
 */
class BlePeripheralService : Service() {

    companion object {
        // logcat tag 慣例（docs/test-plan.md v2.0 §0）
        private const val TAG = Protocol.LOG_TAG
        private const val CHANNEL_ID = "linewatch_ble"
        private const val NOTIF_ID = 1

        /** 藍牙連線狀態（SettingsActivity 藍牙狀態列即時讀取）。 */
        @Volatile
        var bleConnected = false

        /** 已連線裝置名稱（手機），供設定頁顯示。 */
        @Volatile
        var bleDeviceName: String? = null
    }

    private enum class CallState { IDLE, CALLING }

    // BLE 廣播狀態機（避免 async 回呼與 stop/start 交錯的競態）
    private enum class AdvState { IDLE, STARTING, ADVERTISING }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val workerThread = HandlerThread("watch-ble-worker").apply { start() }
    private val workerHandler = Handler(workerThread.looper)

    private var bluetoothManager: BluetoothManager? = null
    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var advState = AdvState.IDLE   // 僅在 worker thread 讀寫

    @Volatile
    private var connectedDevice: BluetoothDevice? = null

    private var callState = CallState.IDLE
    private var currentName: String = "未知聯絡人"
    private var currentKind: String = "voice"

    // D15：息屏期間震動重掛（ColorOS 息屏取消 haptic → 5s 週期重掛直到接通/掛斷/亮屏）
    private var screenOff = false
    private var uiHiddenByScreenOff = false   // 通知畫面看過一次後息屏 → 抬腕不再重顯示
    private val vibrationRearmRunnable = object : Runnable {
        override fun run() {
            if (callState == CallState.CALLING && screenOff) {
                vibratorController.rearmCall()
                mainHandler.postDelayed(this, Protocol.VIBRATION_REARM_MS)
            }
        }
    }

    private fun startVibrationRearmLoop() {
        stopVibrationRearmLoop()
        mainHandler.postDelayed(vibrationRearmRunnable, Protocol.VIBRATION_REARM_MS)
    }

    private fun stopVibrationRearmLoop() {
        mainHandler.removeCallbacks(vibrationRearmRunnable)
    }

    private val watchdog = Watchdog(Protocol.CALL_TIMEOUT_MS) { onWatchdogTimeout() }
    private val vibratorController by lazy { VibratorController(applicationContext) }

    // v2 頭像傳輸 session（protocol.md v2 章節）：同時間僅一個；ts 為 session 鍵
    private var avatarSession: AvatarSession? = null
    private val avatarTimeoutRunnable = Runnable { onAvatarTimeout() }

    /** 目前正在顯示的狀態（CALL/MISSED/DISCONNECTED/null），用於 overlay 延遲檢查的競態防護。 */
    private var displayState: String? = null

    /**
     * 螢幕狀態接收（D15 修正 2026-08-17 使用者二次實測裁決）：
     * - CALLING（對方未掛斷）：息屏只重掛震動（不隱藏畫面）；每次抬腕都重新顯示來電畫面，
     *   直到接通/掛斷。
     * - MISSED/DISCONNECTED（通知類）：顯示一次，息屏即隱藏、抬腕不再重顯。
     * - SCREEN_ON：CALLING 中 → 重掛震動；未接/斷線且未被隱藏過才重試 Activity 路徑。
     */
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> {
                    screenOff = false
                    Protocol.logEvent("{\"t\":\"screen_on\",\"calling\":" + (callState == CallState.CALLING) + "}")
                    if (callState == CallState.CALLING) {
                        vibratorController.rearmCall()
                        stopVibrationRearmLoop()
                    }
                    val st = displayState ?: return
                    if (st == Protocol.STATE_END) return
                    if (IncomingCallActivity.isVisible) return
                    if (st == Protocol.STATE_CALL) {
                        // 未掛斷：每次抬腕都重新顯示來電畫面
                        startCallActivity(st, currentName, currentKind)
                    } else if (!uiHiddenByScreenOff) {
                        startCallActivity(st, currentName, currentKind)
                    }
                }
                Intent.ACTION_SCREEN_OFF -> {
                    screenOff = true
                    Protocol.logEvent(
                        "{\"t\":\"screen_off\",\"calling\":" + (callState == CallState.CALLING) +
                            ",\"visible\":" + IncomingCallActivity.isVisible + ",\"state\":" + displayState + "}"
                    )
                    if (callState == CallState.CALLING) {
                        // ColorOS 息屏會取消震動 → 立即重掛＋5s 週期重掛；畫面不動（抬腕要再看）
                        mainHandler.postDelayed({ vibratorController.rearmCall() }, 400L)
                        startVibrationRearmLoop()
                    } else if (displayState != null && displayState != Protocol.STATE_END) {
                        // 僅通知類（未接/斷線）息屏即隱藏，抬腕不再顯示
                        uiHiddenByScreenOff = true
                        sendBroadcast(Intent(Protocol.ACTION_HIDE_UI).setPackage(packageName))
                        OverlayHelper.dismiss()
                    }
                }
            }
        }
    }

    // ---------- 生命週期 ----------

    override fun onCreate() {
        super.onCreate()
        LogFile.init(this)
        AvatarStore.init(this)
        // v3.10：overlay 下滑關閉 → 本地 endCall(false)（停震；不送 BLE，手機端不受影響）
        OverlayHelper.dismissListener = {
            mainHandler.post {
                if (callState == CallState.CALLING) {
                    endCall(missed = false)
                }
            }
        }
        // v3.20：每次服務建立都自癒 ColorOS 白名單（安裝/更新後不需重開機也能生效）
        ColorOsWhitelist.ensure(this)
        createChannel()
        scheduleKeepAlive()
        startForeground(
            NOTIF_ID,
            buildNotification(getString(R.string.notification_waiting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        workerHandler.post { setupBluetooth() }
        // D15：SCREEN_ON + SCREEN_OFF（息屏隱藏來電畫面＋震動重掛）
        val screenFilter = IntentFilter(Intent.ACTION_SCREEN_ON)
        screenFilter.addAction(Intent.ACTION_SCREEN_OFF)
        registerReceiver(screenStateReceiver, screenFilter)
        screenOff = !(getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action != null) {
            when (action) {
                Protocol.ACTION_DEBUG_START -> {
                    // debug 深連結：IncomingCallActivity 轉交，走與 BLE start 相同路徑
                    val name = intent.getStringExtra(IncomingCallActivity.EXTRA_NAME)
                        ?: getString(R.string.unknown_caller)
                    val kind = intent.getStringExtra(IncomingCallActivity.EXTRA_KIND) ?: "voice"
                    Protocol.logEvent(
                        JSONObject().put("t", "start").put("name", name)
                            .put("kind", kind).put("src", "debug").toString()
                    )
                    mainHandler.post { enterCall(name, kind) }
                }
                Protocol.ACTION_DEBUG_END -> {
                    val missed = intent.getBooleanExtra("missed", false)
                    Protocol.logEvent(
                        JSONObject().put("t", "end").put("missed", missed)
                            .put("src", "debug").toString()
                    )
                    mainHandler.post { endCall(missed) }
                }
                Protocol.ACTION_KEEPALIVE -> {
                    // v3.19：AlarmManager 喚醒自檢；單次 alarm → 檢查後重新排程
                    checkKeepAlive()
                    scheduleKeepAlive()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        watchdog.cancel()
        vibratorController.stop()
        abortAvatarSession()
        AvatarStore.clear()
        displayState = null
        OverlayHelper.dismissListener = null
        OverlayHelper.dismiss()
        cancelKeepAlive()
        stopVibrationRearmLoop()
        try {
            unregisterReceiver(screenStateReceiver)
        } catch (_: IllegalArgumentException) {
        }
        workerHandler.removeCallbacks(advertiseSelfHealRunnable)
        workerHandler.post {
            try {
                gattServer?.close()
            } catch (_: Exception) {
            }
            gattServer = null
            stopAdvertisingLocked()
        }
        workerThread.quitSafely()
        super.onDestroy()
    }

    // ---------- 藍牙初始化（worker thread） ----------

    private fun setupBluetooth() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "BLUETOOTH_CONNECT 未授權（Android 12+）→ 跳過 BLE")
            updateNotification(getString(R.string.notification_no_bt_permission))
            return
        }
        bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter
        if (adapter == null) {
            Log.e(TAG, "本機不支援藍牙")
            updateNotification(getString(R.string.notification_bt_unavailable))
            return
        }
        if (!adapter.isEnabled) {
            Log.w(TAG, "藍牙未開啟")
            updateNotification(getString(R.string.notification_bt_off))
            return
        }
        openGattServer()
        startAdvertising()
        // 自癒保險：每 30s 檢查「無連線卻未在廣播」
        workerHandler.removeCallbacks(advertiseSelfHealRunnable)
        workerHandler.postDelayed(advertiseSelfHealRunnable, 30_000L)
    }

    private fun openGattServer() {
        if (gattServer != null) return
        gattServer = bluetoothManager?.openGattServer(this, gattServerCallback)
        if (gattServer == null) {
            Log.e(TAG, "openGattServer 失敗")
            return
        }
        val service = BluetoothGattService(
            UUID.fromString(Protocol.SERVICE_UUID),
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )
        val cmdChar = BluetoothGattCharacteristic(
            UUID.fromString(Protocol.CHAR_CMD_UUID),
            BluetoothGattCharacteristic.PROPERTY_WRITE or
                BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
            BluetoothGattCharacteristic.PERMISSION_WRITE
        )
        val stateChar = BluetoothGattCharacteristic(
            UUID.fromString(Protocol.CHAR_STATE_UUID),
            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            BluetoothGattCharacteristic.PERMISSION_READ
        )
        // CCCD：讓手機端可訂閱 notify
        val cccd = BluetoothGattDescriptor(
            UUID.fromString(Protocol.CCCD_UUID),
            BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
        )
        stateChar.addDescriptor(cccd)
        service.addCharacteristic(cmdChar)
        service.addCharacteristic(stateChar)
        gattServer?.addService(service)
        Protocol.logI("GattServer 已建立（NUS）")
    }

    /** 要求開始廣播（斷線後）。所有廣告操作在 worker thread 序列化，避免 stop/start 競態。 */
    private fun startAdvertising() {
        workerHandler.post { startAdvertisingLocked() }
    }

    private fun startAdvertisingLocked() {
        if (advState != AdvState.IDLE) return
        if (connectedDevice != null) return   // 已有連線 → 不需廣播
        val adapter = bluetoothManager?.adapter ?: return
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        advertiser = adapter.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.e(TAG, "本機不支援 BLE 廣播")
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        // AD 上限 31 bytes：AD 只放 NUS UUID（21 bytes 恆不超限），裝置名放 scan response
        val adData = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(UUID.fromString(Protocol.SERVICE_UUID)))
            .build()
        val scanResponseBuilder = AdvertiseData.Builder()
        val deviceName = adapter.name
        if (deviceName != null && deviceName.length <= 29) {
            scanResponseBuilder.setIncludeDeviceName(true)
        }
        val scanResponse = scanResponseBuilder.build()
        // 樂觀標記 STARTING：pending start 若被 stop 要求，stopAdvertisingLocked 才能正確取消
        advState = AdvState.STARTING
        try {
            advertiser?.startAdvertising(settings, adData, scanResponse, advertiseCallback)
        } catch (e: SecurityException) {
            advState = AdvState.IDLE
            Log.w(TAG, "startAdvertising SecurityException", e)
            scheduleAdvertiseRetry()
        }
    }

    /** 要求停止廣播（連線建立後）。 */
    private fun stopAdvertising() {
        workerHandler.post { stopAdvertisingLocked() }
    }

    private fun stopAdvertisingLocked() {
        if (advState == AdvState.STARTING || advState == AdvState.ADVERTISING) {
            try {
                advertiser?.stopAdvertising(advertiseCallback)
            } catch (_: Exception) {
            }
        }
        advState = AdvState.IDLE
    }

    private val advertiseCallback: AdvertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            if (advState == AdvState.STARTING) {
                advState = AdvState.ADVERTISING
                Protocol.logI("BLE 廣播開始（LOW_LATENCY）")
            } else {
                // 成功回呼前已被要求停止（如快速重連）→ 補一次停止
                Log.w(TAG, "廣播成功回呼時狀態非 STARTING → 補停止")
                try {
                    advertiser?.stopAdvertising(this)   // this = 本 callback 實例（避免自引用初始化問題）
                } catch (_: Exception) {
                }
            }
        }

        override fun onStartFailure(errorCode: Int) {
            advState = AdvState.IDLE
            Log.w(TAG, "BLE 廣播失敗 errorCode=$errorCode → 5s 後重試")
            scheduleAdvertiseRetry()
        }
    }

    private val advertiseRetryRunnable = Runnable {
        startAdvertisingLocked()   // 已於 worker thread；IDLE 且無連線才真正啟動
    }

    private fun scheduleAdvertiseRetry() {
        workerHandler.removeCallbacks(advertiseRetryRunnable)
        workerHandler.postDelayed(advertiseRetryRunnable, 5_000L)
    }

    // ---------- v3.19 ColorOS 凍結自癒（AlarmManager 喚醒） ----------

    private fun scheduleKeepAlive() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = keepAlivePendingIntent()
        val triggerAt = android.os.SystemClock.elapsedRealtime() + Protocol.KEEPALIVE_INTERVAL_MS
        try {
            if (Build.VERSION.SDK_INT >= 31 && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
        }
    }

    private fun cancelKeepAlive() {
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(keepAlivePendingIntent())
        } catch (_: Exception) {
        }
    }

    private fun keepAlivePendingIntent(): PendingIntent {
        return PendingIntent.getBroadcast(
            this,
            1001,
            Intent(this, KeepAliveReceiver::class.java).setAction(Protocol.ACTION_KEEPALIVE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** 喚醒自檢：無連線且廣告非 ACTIVE → 重啟；GattServer 不存在 → 重建；連線中僅 debug。 */
    private fun checkKeepAlive() {
        workerHandler.post {
            val connected = connectedDevice != null
            val advActive = advState == AdvState.ADVERTISING || advState == AdvState.STARTING
            if (!connected && !advActive) {
                Log.w(TAG, "keepalive：無連線且未在廣播 → 重建/重啟")
                if (bluetoothManager == null) {
                    setupBluetooth()
                } else {
                    if (gattServer == null) openGattServer()
                    startAdvertisingLocked()
                }
            } else {
                Protocol.logD("keepalive：狀態正常（connected=$connected adv=$advActive）")
            }
            Protocol.logEvent(
                JSONObject().put("t", "keepalive_check")
                    .put("connected", connected)
                    .put("advertising", advActive).toString()
            )
        }
    }

    /** 自癒保險：每 30s 檢查「無連線卻未在廣播」→ 重啟（防 stack 回呼被吞的極端競態）。 */
    private val advertiseSelfHealRunnable = object : Runnable {
        override fun run() {
            startAdvertisingLocked()
            workerHandler.postDelayed(this, 30_000L)
        }
    }

    // ---------- GATT 回呼（binder thread） ----------

    private val gattServerCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Protocol.logI("手機已連線: ${device.address}")
                Protocol.logEvent(
                    JSONObject().put("t", "connect").put("addr", device.address).toString()
                )
                connectedDevice = device
                bleConnected = true
                bleDeviceName = try {
                    device.name
                } catch (e: SecurityException) {
                    device.address
                }
                broadcastConnState()
                mainHandler.post {
                    stopAdvertising()
                    updateNotification(getString(R.string.notification_connected))
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Protocol.logI("手機已斷線: ${device.address}")
                Protocol.logEvent(
                    JSONObject().put("t", "disconnect").put("addr", device.address).toString()
                )
                connectedDevice = null
                bleConnected = false
                bleDeviceName = null
                abortAvatarSession()      // 斷線 → 中止未完成頭像 session
                broadcastConnState()
                mainHandler.post {
                    updateNotification(getString(R.string.notification_waiting))
                    startAdvertising()
                    if (!vibratorController.isCalling()) {
                        // 非震動中 → 顯示「藍牙已斷線」提示（protocol.md）
                        showStateUi(Protocol.STATE_DISCONNECTED, null, null)
                    }
                    // 震動中 → 照跑 120s watchdog 收尾，不中斷提醒
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (characteristic.uuid == UUID.fromString(Protocol.CHAR_CMD_UUID) && offset == 0) {
                Protocol.logD("收到指令: ${String(value, Charsets.UTF_8)}")
                val cmd = Protocol.parseCommand(value)
                mainHandler.post { handleCommand(cmd) }
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
                }
            } else {
                if (responseNeeded) {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (offset != 0) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, 0, null)
            } else {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, ByteArray(0))
            }
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            // CCCD（手機訂閱 notify）
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    // ---------- 狀態機（main thread） ----------

    private fun handleCommand(cmd: Protocol.Command?) {
        if (cmd == null) {
            Protocol.logD("無效指令，忽略")
            return
        }
        when (cmd.t) {
            "start" -> {
                val name = cmd.name?.let { Protocol.truncateName(it) }
                    ?: getString(R.string.unknown_caller)
                val kind = if (cmd.kind == "video") "video" else "voice"
                Protocol.logEvent(
                    JSONObject().put("t", "start").put("name", name).put("kind", kind).toString()
                )
                enterCall(name, kind)
                notifyState(Protocol.ack("start"))
            }
            "end" -> {
                Protocol.logEvent(
                    JSONObject().put("t", "end").put("missed", cmd.missed).toString()
                )
                endCall(cmd.missed)
                notifyState(Protocol.ack("end"))
            }
            "missed" -> {
                // protocol.md v1.1：接聽/拒接停止後，手機未接判定窗內補送
                val name = cmd.name?.let { Protocol.truncateName(it) } ?: currentName
                val kind = if (cmd.kind == "video") "video" else "voice"
                Protocol.logEvent(
                    JSONObject().put("t", "missed").put("name", name).put("kind", kind).toString()
                )
                handleMissedCommand(name, kind)
                notifyState(Protocol.ack("missed"))
            }
            "ping" -> {
                Protocol.logEvent(JSONObject().put("t", "ping").put("seq", cmd.seq).toString())
                notifyState(Protocol.pong(cmd.seq))
            }
            "av_start" -> handleAvatarStart(cmd)
            "av_chunk" -> handleAvatarChunk(cmd)
            "av_end" -> handleAvatarEnd(cmd)
            else -> Protocol.logD("未知指令類型忽略: ${cmd.t}")
        }
    }

    // ---------- v2 頭像傳輸（protocol.md v2 章節） ----------

    private class AvatarSession(val ts: Long, val total: Int, val bytes: Int) {
        val chunks = arrayOfNulls<ByteArray>(total)
        var received = 0

        fun put(index: Int, data: ByteArray): Boolean {
            if (index < 0 || index >= total || chunks[index] != null) return false
            chunks[index] = data
            received++
            return true
        }

        fun complete(): Boolean = received == total
    }

    private fun handleAvatarStart(cmd: Protocol.Command) {
        abortAvatarSession()   // 同時間僅一個 session，新 av_start 取代舊的
        if (cmd.total <= 0 || cmd.total > 64 || cmd.bytes <= 0 || cmd.bytes > 12_000) {
            Log.w(TAG, "av_start 參數不合理: total=${cmd.total} bytes=${cmd.bytes}，忽略")
            return
        }
        avatarSession = AvatarSession(cmd.ts, cmd.total, cmd.bytes)
        Protocol.logEvent(
            JSONObject().put("t", "av_start").put("ts", cmd.ts)
                .put("total", cmd.total).put("bytes", cmd.bytes).toString()
        )
        mainHandler.removeCallbacks(avatarTimeoutRunnable)
        mainHandler.postDelayed(avatarTimeoutRunnable, Protocol.AVATAR_TIMEOUT_MS)
    }

    private fun handleAvatarChunk(cmd: Protocol.Command) {
        val session = avatarSession ?: return
        if (cmd.ts != session.ts || cmd.index < 0 || cmd.data == null) return
        val raw = try {
            Base64.decode(cmd.data, Base64.DEFAULT)
        } catch (e: Exception) {
            null
        }
        if (raw == null || raw.isEmpty() || !session.put(cmd.index, raw)) return
        Protocol.logEvent(
            JSONObject().put("t", "av_chunk").put("ts", cmd.ts).put("i", cmd.index).toString()
        )
    }

    private fun handleAvatarEnd(cmd: Protocol.Command) {
        val session = avatarSession ?: return
        if (cmd.ts != session.ts) return
        mainHandler.removeCallbacks(avatarTimeoutRunnable)
        avatarSession = null
        finalizeAvatarSession(session, cmd.sha)
    }

    /** 拼接→SHA-256 比對→解碼→顯示。回傳是否成功（供 log）。 */
    private fun finalizeAvatarSession(session: AvatarSession, shaHex: String?) {
        if (!session.complete()) {
            Protocol.logEvent(
                JSONObject().put("t", "av_fail").put("ts", session.ts).put("reason", "missing").toString()
            )
            notifyState(Protocol.avFail(session.ts, "missing"))
            return
        }
        val totalBytes = session.chunks.sumOf { it!!.size }
        if (totalBytes != session.bytes) {
            Protocol.logEvent(
                JSONObject().put("t", "av_fail").put("ts", session.ts).put("reason", "sha").toString()
            )
            notifyState(Protocol.avFail(session.ts, "sha"))
            return
        }
        val out = ByteArray(totalBytes)
        var off = 0
        for (c in session.chunks) {
            val b = c!!
            System.arraycopy(b, 0, out, off, b.size)
            off += b.size
        }
        // SHA-256（原始 JPEG 位元組）
        val digest = try {
            MessageDigest.getInstance("SHA-256").digest(out)
        } catch (e: Exception) {
            null
        }
        val hex = digest?.joinToString("") { "%02x".format(it) }
        if (hex == null || (shaHex != null && !hex.equals(shaHex, ignoreCase = true))) {
            Protocol.logEvent(
                JSONObject().put("t", "av_fail").put("ts", session.ts).put("reason", "sha").toString()
            )
            notifyState(Protocol.avFail(session.ts, "sha"))
            return
        }
        // 解碼 JPEG
        val bmp = try {
            BitmapFactory.decodeByteArray(out, 0, out.size)
        } catch (e: Exception) {
            null
        }
        if (bmp == null) {
            Protocol.logEvent(
                JSONObject().put("t", "av_fail").put("ts", session.ts).put("reason", "sha").toString()
            )
            notifyState(Protocol.avFail(session.ts, "sha"))
            return
        }
        // 成功：驗證通過即寫入 name 快取（ui-spec v3）；僅 CALLING 中替換顯示
        AvatarStore.put(currentName, bmp)
        if (callState == CallState.CALLING) {
            AvatarStore.set(bmp)
            broadcastAvatar()
            if (OverlayHelper.isShowing()) {
                // overlay 備援顯示中 → 原地重渲染（含新頭像）
                OverlayHelper.show(this, OverlayHelper.Mode.CALL, currentName, currentKind)
            }
            Protocol.logEvent(
                JSONObject().put("t", "av_show").put("ts", session.ts).put("displayed", true).toString()
            )
        } else {
            // 晚到（已 end）→ 不顯示，但已寫快取（下一通同來電者秒顯）；仍 ack 免手機重試
            Protocol.logEvent(
                JSONObject().put("t", "av_show").put("ts", session.ts).put("displayed", false).toString()
            )
        }
        notifyState(Protocol.ack("av_end"))
    }

    private fun onAvatarTimeout() {
        val session = avatarSession ?: return
        avatarSession = null
        Protocol.logEvent(
            JSONObject().put("t", "av_fail").put("ts", session.ts).put("reason", "timeout").toString()
        )
        notifyState(Protocol.avFail(session.ts, "timeout"))
    }

    /** 靜默中止 session（新 start/end/missed/BLE 斷線）；不發 av_fail（手機端同條件也會中止）。 */
    private fun abortAvatarSession() {
        mainHandler.removeCallbacks(avatarTimeoutRunnable)
        avatarSession = null
    }

    private fun broadcastAvatar() {
        sendBroadcast(Intent(Protocol.ACTION_AVATAR).setPackage(packageName))
    }

    /** log 用：名字雜湊（不落地原始名字）。 */
    private fun cacheSha(name: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(name.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }

    private fun enterCall(name: String, kind: String) {
        currentName = name
        currentKind = kind
        callState = CallState.CALLING
        uiHiddenByScreenOff = false   // D15：新狀態重置「顯示一次」旗標
        abortAvatarSession()             // 新通來電 → 中止未完成頭像 session
        AvatarStore.clear()              // 清空前一通記憶體頭像
        if (AvatarStore.loadForName(name)) {
            // 頭像快取命中 → 秒顯真照片（不等傳輸）；未命中 → 首字 → 傳輸到達後替換
            Protocol.logEvent(
                JSONObject().put("t", "av_cache").put("action", "hit")
                    .put("sha", cacheSha(name)).toString()
            )
        }
        vibratorController.startCall()   // 重入保護：已在循環中則 no-op
        watchdog.arm()                   // 120s 看門狗
        updateNotification(getString(R.string.notification_calling, name))
        showStateUi(Protocol.STATE_CALL, name, kind)
        Protocol.logI("進入 CALLING：$name（$kind）")
    }

    private fun endCall(missed: Boolean) {
        val wasCalling = callState == CallState.CALLING
        callState = CallState.IDLE
        // v1.0.1b：不中止頭像 session——讓 av_end 收尾並寫快取（下一通同人秒顯）；
        // 新 av_start／5s 逾時／BLE 斷線仍會清理半截 session。
        AvatarStore.clear()              // 清記憶體；未接/斷線畫面依名字查磁碟快取
        watchdog.cancel()
        vibratorController.stop()
        if (!wasCalling && !missed) {
            Protocol.logD("IDLE 收到 end(missed=false)，無需動作")
            return
        }
        if (missed) {
            // 未接：短促單次震動＋「LINE 未接來電」畫面（8s 自動關）
            vibratorController.startMissed()
            updateNotification(getString(R.string.notification_waiting))
            showStateUi(Protocol.STATE_MISSED, currentName, currentKind)
            Protocol.logI("未接來電：$currentName")
        } else {
            updateNotification(getString(R.string.notification_waiting))
            showStateUi(Protocol.STATE_END, currentName, currentKind)
            Protocol.logI("來電結束")
        }
    }

    /**
     * protocol.md v1.1 missed 補送指令：
     * - RINGING 收到 → 視同 end(missed=true)（未接短震＋未接畫面）
     * - IDLE 收到 → 僅顯示「LINE 未接來電」畫面 8s，不震動（end(false) 已停震）
     * 防洪流由手機端一次性未接判定窗保證（窗長見 protocol.md），本端無需額外防護。
     */
    private fun handleMissedCommand(name: String, kind: String) {
        currentName = name
        currentKind = kind
        if (callState == CallState.CALLING) {
            Protocol.logI("RINGING 收到 missed → 視同 end(missed=true)")
            endCall(missed = true)
        } else {
            callState = CallState.IDLE
            // v1.0.1b：不中止頭像 session（見 endCall 註解）
            AvatarStore.clear()           // 清記憶體；未接畫面依名字查磁碟快取
            watchdog.cancel()
            updateNotification(getString(R.string.notification_waiting))
            showStateUi(Protocol.STATE_MISSED, name, kind)
            Protocol.logI("IDLE 收到 missed → 顯示未接畫面（不震動）：$name")
        }
    }

    private fun onWatchdogTimeout() {
        Log.w(TAG, "120s 看門狗觸發 → 視為未接")
        Protocol.logEvent(
            JSONObject().put("t", "watchdog").put("timeoutMs", Protocol.CALL_TIMEOUT_MS).toString()
        )
        if (callState == CallState.CALLING) {
            endCall(missed = true)
        }
    }

    // ---------- UI 調度（main thread） ----------

    private fun showStateUi(state: String, name: String?, kind: String?) {
        broadcastState(state, name, kind)
        if (state == Protocol.STATE_END) {
            // 結束：關閉 overlay（Activity 若可見會自行 finish）
            displayState = null
            OverlayHelper.dismiss()
            return
        }
        displayState = state
        uiHiddenByScreenOff = false   // D15：每次新顯示（含未接/斷線）都可再顯示一次
        if (IncomingCallActivity.isVisible) return   // Activity 前台 → broadcast 已更新畫面
        // 主路徑：FGS startActivity（螢幕亮著時有效）
        startCallActivity(state, name, kind)
        // 備援：P7 實測背景啟動會被系統靜默 Abort（不拋例外）→ 1.5s 後仍不可見改走 overlay
        mainHandler.postDelayed({
            if (displayState == state && !IncomingCallActivity.isVisible) {
                OverlayHelper.show(
                    this,
                    overlayMode(state),
                    name ?: currentName,
                    kind ?: currentKind
                )
            }
        }, 1500L)
    }

    private fun overlayMode(state: String): OverlayHelper.Mode = when (state) {
        Protocol.STATE_MISSED -> OverlayHelper.Mode.MISSED
        Protocol.STATE_DISCONNECTED -> OverlayHelper.Mode.DISCONNECTED
        else -> OverlayHelper.Mode.CALL
    }

    private fun broadcastConnState() {
        val intent = Intent(Protocol.ACTION_CONN_STATE)
            .setPackage(packageName)
            .putExtra(Protocol.EXTRA_CONNECTED, bleConnected)
            .putExtra("device", bleDeviceName)
        sendBroadcast(intent)
    }

    private fun broadcastState(state: String, name: String?, kind: String?) {
        val intent = Intent(Protocol.ACTION_STATE)
            .setPackage(packageName)
            .putExtra(Protocol.EXTRA_STATE, state)
        name?.let { intent.putExtra(IncomingCallActivity.EXTRA_NAME, it) }
        kind?.let { intent.putExtra(IncomingCallActivity.EXTRA_KIND, it) }
        sendBroadcast(intent)
    }

    private fun startCallActivity(state: String, name: String?, kind: String?) {
        val intent = Intent(this, IncomingCallActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(IncomingCallActivity.EXTRA_FROM_SERVICE, true)
            .putExtra(IncomingCallActivity.EXTRA_NAME, name ?: currentName)
            .putExtra(IncomingCallActivity.EXTRA_KIND, kind ?: currentKind)
        when (state) {
            Protocol.STATE_MISSED ->
                intent.putExtra(IncomingCallActivity.EXTRA_MISSED, true)
            Protocol.STATE_DISCONNECTED ->
                intent.putExtra(IncomingCallActivity.EXTRA_DISCONNECTED, true)
            else ->
                intent.putExtra(IncomingCallActivity.EXTRA_MISSED, false)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            // 備援路徑：Activity 啟動直接拋例外（如 SecurityException）→ 立即 overlay
            Log.w(TAG, "startActivity 失敗 → overlay 備援", e)
            OverlayHelper.show(
                this,
                overlayMode(state),
                name ?: currentName,
                kind ?: currentKind
            )
        }
    }

    // ---------- 通知與 notify ----------

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        )
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    /** CHAR_STATE notify（ack/pong）。BLE 操作固定回 worker thread，保持順序。 */
    private fun notifyState(json: String) {
        workerHandler.post {
            val device = connectedDevice ?: return@post
            val gs = gattServer ?: return@post
            val service = gs.getService(UUID.fromString(Protocol.SERVICE_UUID)) ?: return@post
            val ch = service.getCharacteristic(UUID.fromString(Protocol.CHAR_STATE_UUID)) ?: return@post
            ch.value = json.toByteArray(Charsets.UTF_8)
            Protocol.logEvent(json)   // ack / pong（送出即記錄，測試依此對照）
            gs.notifyCharacteristicChanged(device, ch, false)
        }
    }
}
