package com.linewatch.phone;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.SystemClock;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.ParcelUuid;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * 手機端 BLE Central 前台服務（type=connectedDevice）。
 * 掃描過濾 NUS UUID → autoConnect → requestMtu(247) → 寫 CHAR_CMD；
 * 心跳 10s（連續 3 次無 pong 重連）；重連 backoff 5s/15s/30s。
 * 規格：docs/protocol.md、docs/architecture.md。logcat tag：LineWatchPhone。
 */
public class BleCentralService extends Service {

    private static final String TAG = "LineWatchPhone";
    private static volatile BleCentralService instance;

    private BluetoothAdapter adapter;
    private BluetoothLeScanner scanner;
    private BluetoothGatt gatt;
    private BluetoothGattCharacteristic charCmd;
    private BluetoothGattCharacteristic charState;

    private HandlerThread workerThread;
    private Handler worker;

    private volatile boolean ready = false;
    private volatile boolean stopping = false;
    private volatile boolean connecting = false;
    private volatile boolean scanning = false;
    private volatile boolean reconnectScheduled = false;
    private boolean heartbeatStarted = false;

    private int reconnectAttempt = 0;
    private int pingSeq = 0;
    private int pongMisses = 0;

    private String pending = null;
    private String lastWritten = null;
    private String deviceName = "";

    // ---- 頭像傳輸 session（protocol v2 草案；僅在 worker thread 存取） ----
    private byte[] avatarJpeg = null;
    private String avatarSha = null;
    private int avatarTotal = 0;
    private int avatarIdx = 0;
    private long avatarTs = 0;
    private int avatarRetries = 0;
    private boolean avatarAwaitingAck = false;

    // ---- 串列化寫入佇列（T8 聯測修正：GATT 單寫入佇列，writeCharacteristic false ≠ 失敗） ----
    private static final class WriteItem {
        final String json;
        final boolean withResponse;
        WriteItem(String json, boolean withResponse) {
            this.json = json;
            this.withResponse = withResponse;
        }
    }
    private final java.util.ArrayDeque<WriteItem> commandQueue = new java.util.ArrayDeque<>();
    private final java.util.ArrayDeque<WriteItem> avatarQueue = new java.util.ArrayDeque<>();
    private boolean writeInFlight = false;
    private int writeFalseCount = 0;

    // ---------- 靜態 API（MainActivity／LineCallListenerService 使用） ----------

    public static boolean isRunning() {
        return instance != null;
    }

    public static boolean isReady() {
        BleCentralService s = instance;
        return s != null && s.ready;
    }

    /** 送一則 JSON 指令到 CHAR_CMD；未就緒時排入 pending（連上後由同步邏輯取代）。 */
    public static boolean send(String json) {
        BleCentralService s = instance;
        if (s == null) {
            Log.w(TAG, "send ignored (service not running): " + json);
            return false;
        }
        return s.write(json);
    }

    /** 開始傳送頭像（protocol v2）：取代任何進行中的 session；未連線則忽略。 */
    public static boolean sendAvatar(byte[] jpeg) {
        BleCentralService s = instance;
        if (s == null || jpeg == null || jpeg.length == 0) {
            Log.w(TAG, "sendAvatar ignored (service=" + (s != null) + ", jpeg=" + (jpeg == null ? -1 : jpeg.length) + ")");
            return false;
        }
        s.worker.post(() -> s.startAvatarSession(jpeg));
        return true;
    }

    /** 中止進行中的頭像傳輸（call end／missed／ongoing／斷線時呼叫）。 */
    public static void abortAvatar() {
        BleCentralService s = instance;
        if (s != null) {
            s.worker.post(s::finishAvatarSession);
        }
    }

    // ---------- 生命週期 ----------

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        workerThread = new HandlerThread("ble-worker");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        adapter = bm == null ? null : bm.getAdapter();
        if (adapter != null) scanner = adapter.getBluetoothLeScanner();

        createChannel();
        startForegroundInternal();
        ContextCompat.registerReceiver(this, btReceiver,
                new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED);
        Logs.i(TAG, "BleCentralService created");
        StatusBus.ble("已斷線", "");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (Constants.ACTION_STOP.equals(action)) {
            Logs.i(TAG, "stop requested");
            stopping = true;
            ready = false;
            worker.post(() -> finishAvatarSession());
            stopScan();
            closeGatt();
            stopSelf();
            return START_NOT_STICKY;
        }
        stopping = false;
        startScanSafe();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        Logs.i(TAG, "BleCentralService destroyed");
        stopping = true;
        instance = null;
        finishAvatarSession();
        try {
            unregisterReceiver(btReceiver);
        } catch (Exception ignored) {
        }
        stopScan();
        closeGatt();
        if (workerThread != null) workerThread.quitSafely();
        StatusBus.ble("已斷線", "");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------- 前台通知 ----------

    private void createChannel() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                Constants.CHANNEL_BLE, getString(R.string.ble_notif_title), NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
    }

    private void startForegroundInternal() {
        Intent ni = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, ni, PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, Constants.CHANNEL_BLE)
                .setSmallIcon(R.drawable.ic_stat_call)
                .setContentTitle(getString(R.string.ble_notif_title))
                .setContentText(getString(R.string.ble_notif_text))
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        ServiceCompat.startForeground(this, Constants.NOTIFICATION_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
    }

    // ---------- 掃描與連線 ----------

    private boolean hasBlePermissions() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startScanSafe() {
        worker.post(() -> {
            if (stopping) return;
            if (scanning || connecting || gatt != null) return;
            if (adapter == null || !adapter.isEnabled()) {
                StatusBus.ble("已斷線", "");
                Log.w(TAG, "bluetooth adapter off");
                return;
            }
            if (!hasBlePermissions()) {
                StatusBus.ble("已斷線", "");
                Log.w(TAG, "missing BLUETOOTH_SCAN/CONNECT permission");
                return;
            }
            if (scanner == null) scanner = adapter.getBluetoothLeScanner();
            if (scanner == null) {
                Log.w(TAG, "no BluetoothLeScanner");
                return;
            }
            try {
                ScanFilter f = new ScanFilter.Builder()
                        .setServiceUuid(new ParcelUuid(Constants.UUID_SERVICE))
                        .build();
                ScanSettings s = new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                        .build();
                scanning = true;
                StatusBus.ble("掃描中", "");
                Logs.i(TAG, "scan started (NUS UUID filter)");
                scanner.startScan(Collections.singletonList(f), s, scanCallback);
            } catch (Exception e) {
                scanning = false;
                Log.e(TAG, "startScan failed", e);
                scheduleReconnect();
            }
        });
    }

    private void stopScan() {
        if (scanner != null && scanning) {
            try {
                scanner.stopScan(scanCallback);
            } catch (Exception ignored) {
            }
        }
        scanning = false;
    }

    private final ScanCallback scanCallback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            if (connecting || gatt != null) return;
            String want = Prefs.getTargetName(BleCentralService.this);
            String name = result.getDevice().getName();
            if (!want.isEmpty() && (name == null || !name.toLowerCase().contains(want.toLowerCase()))) {
                return;
            }
            stopScan();
            connectTo(result.getDevice());
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "scan failed: " + errorCode);
            scanning = false;
            StatusBus.ble("已斷線", "");
            scheduleReconnect();
        }
    };

    private void connectTo(BluetoothDevice device) {
        if (stopping || connecting) return;
        if (!hasBlePermissions()) return;
        connecting = true;
        deviceName = device.getName() != null ? device.getName() : device.getAddress();
        Logs.i(TAG, "connectGatt (autoConnect=true) -> " + deviceName + " (" + device.getAddress() + ")");
        gatt = device.connectGatt(this, true, gattCallback, BluetoothDevice.TRANSPORT_LE);
        if (gatt == null) {
            connecting = false;
            Log.w(TAG, "connectGatt returned null");
            scheduleReconnect();
        }
    }

    // ---------- GATT callback ----------

    private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Logs.i(TAG, "connected (status=" + status + ")");
                connecting = false;
                reconnectAttempt = 0;
                pongMisses = 0;
                StatusBus.ble("已連線", deviceName);
                if (!g.discoverServices()) {
                    Log.w(TAG, "discoverServices returned false");
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Logs.i(TAG, "disconnected (status=" + status + ")");
                ready = false;
                connecting = false;
                StatusBus.ble("已斷線", deviceName);
                worker.post(() -> finishAvatarSession());
                closeGatt();
                if (!stopping) scheduleReconnect();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "discoverServices failed: " + status);
                closeGatt();
                if (!stopping) scheduleReconnect();
                return;
            }
            BluetoothGattService svc = g.getService(Constants.UUID_SERVICE);
            if (svc == null) {
                Log.w(TAG, "NUS service not found");
                closeGatt();
                if (!stopping) scheduleReconnect();
                return;
            }
            charCmd = svc.getCharacteristic(Constants.UUID_CHAR_CMD);
            charState = svc.getCharacteristic(Constants.UUID_CHAR_STATE);
            if (charCmd == null || charState == null) {
                Log.w(TAG, "NUS characteristics not found");
                closeGatt();
                if (!stopping) scheduleReconnect();
                return;
            }
            charCmd.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
            if (!g.requestMtu(Constants.MTU_REQUEST)) {
                Log.w(TAG, "requestMtu returned false");
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt g, int mtu, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS || mtu < 200) {
                Log.w(TAG, "mtu change failed or too small: mtu=" + mtu + " status=" + status);
                closeGatt();
                if (!stopping) scheduleReconnect();
                return;
            }
            Logs.i(TAG, "mtu = " + mtu);
            enableNotifications(g);
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt g, BluetoothGattCharacteristic ch, int status) {
            writeInFlight = false; // 回呼到達 → 序列化器可送下一筆
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Logs.i(TAG, "write ok: " + lastWritten);
                if (lastWritten != null) {
                    String t = Command.typeOf(lastWritten);
                    if ("start".equals(t) || "end".equals(t)) {
                        LineCallListenerService.notifyCommandWritten(lastWritten);
                    } else if ("av_start".equals(t)) {
                        worker.post(() -> avatarChunkWriter.run()); // 開始排分塊
                    } else if ("av_end".equals(t)) {
                        worker.post(() -> {
                            avatarAwaitingAck = true;
                            worker.removeCallbacks(avatarAckTimeout);
                            worker.postDelayed(avatarAckTimeout, AvatarTransfer.ACK_TIMEOUT_MS);
                        });
                    }
                }
            } else {
                Log.w(TAG, "write failed: status=" + status + " (" + lastWritten + ")");
                pongMisses++;
                if (lastWritten != null) {
                    String t = Command.typeOf(lastWritten);
                    if ("av_start".equals(t) || "av_end".equals(t)) {
                        worker.post(() -> finishAvatarSession());
                    }
                }
            }
            worker.post(writePump);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt g, BluetoothGattCharacteristic ch) {
            byte[] v = ch.getValue();
            if (v == null) return;
            String json = new String(v, StandardCharsets.UTF_8);
            String t = Command.typeOf(json);
            if ("pong".equals(t)) {
                pongMisses = 0;
                Logs.d(TAG, "pong received: " + json);
            } else if ("ack".equals(t)) {
                Logs.i(TAG, "ack received: " + json);
                if (json.contains("av_end")) {
                    worker.post(() -> {
                        if (avatarAwaitingAck) {
                            Logs.i(TAG, "avatar transfer done (ack av_end)");
                            finishAvatarSession();
                        }
                    });
                }
            } else if ("av_fail".equals(t)) {
                Log.w(TAG, "avatar fail received: " + json);
                worker.post(() -> handleAvatarFail());
            } else if ("av_show".equals(t)) {
                // 手錶端顯示結果（可能走 BLE 或僅其本機 log）：僅記錄，不動作
                Logs.i(TAG, "avatar show event: " + json);
            } else {
                Logs.d(TAG, "unexpected notify: " + json);
            }
        }
    };

    private void enableNotifications(BluetoothGatt g) {
        if (g != gatt) return;
        gatt.setCharacteristicNotification(charState, true);
        BluetoothGattDescriptor cccd = charState.getDescriptor(Constants.UUID_CCCD);
        if (cccd != null) {
            cccd.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            gatt.writeDescriptor(cccd);
        }
        ready = true;
        StatusBus.ble("已連線", deviceName);
        LineCallListenerService.notifyBleReady();
        if (pending != null) {
            Logs.i(TAG, "discard stale pending command: " + pending);
            pending = null;
        }
        startHeartbeat();
        worker.post(writePump); // 連線就緒：恢復佇列序列化
    }

    // ---------- 寫入 ----------

    /** 指令寫入（start/end/missed/ping）：進 commandQueue（優先於頭像佇列），由 writePump 序列化送出。 */
    private synchronized boolean write(String json) {
        if (!ready || gatt == null || charCmd == null) {
            pending = json;
            Logs.d(TAG, "not ready, queued: " + json);
            return false;
        }
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > Constants.MAX_MSG_BYTES) {
            Log.w(TAG, "message exceeds " + Constants.MAX_MSG_BYTES + " bytes: " + bytes.length);
        }
        commandQueue.add(new WriteItem(json, true));
        worker.post(writePump);
        return true;
    }

    // ---------- 頭像傳輸（protocol v2 草案；worker thread） ----------

    private void startAvatarSession(byte[] jpeg) {
        if (stopping) return;
        finishAvatarSession(); // 取代舊 session
        avatarJpeg = jpeg;
        avatarSha = AvatarTransfer.sha256Hex(jpeg);
        avatarTotal = AvatarTransfer.chunkCount(jpeg.length);
        avatarIdx = 0;
        avatarRetries = 0;
        avatarAwaitingAck = false;
        avatarTs = SystemClock.elapsedRealtime();
        if (avatarTotal <= 0 || !ready) {
            Log.w(TAG, "avatar session skipped (total=" + avatarTotal + " ready=" + ready + ")");
            finishAvatarSession();
            return;
        }
        Logs.i(TAG, "avatar session start: ts=" + avatarTs + " total=" + avatarTotal + " bytes=" + jpeg.length);
        // av_start 進串列化佇列（排在進行中的 start/ping 之後）；false 由 writePump 重試，不再是立即失敗
        avatarQueue.add(new WriteItem(AvatarTransfer.buildStart(avatarTotal, jpeg.length, avatarTs), true));
        worker.post(writePump);
        worker.removeCallbacks(avatarTimeout);
        worker.postDelayed(avatarTimeout, AvatarTransfer.TIMEOUT_MS);
    }

    private void finishAvatarSession() {
        if (avatarJpeg != null) {
            Logs.i(TAG, "avatar session finished (idx=" + avatarIdx + "/" + avatarTotal + ")");
        }
        avatarJpeg = null;
        avatarSha = null;
        avatarTotal = 0;
        avatarIdx = 0;
        avatarTs = 0;
        avatarRetries = 0;
        avatarAwaitingAck = false;
        worker.removeCallbacks(avatarTimeout);
        worker.removeCallbacks(avatarAckTimeout);
        avatarQueue.clear(); // 未送出的 av_* 全部丟棄（中止語義）
    }

    /** av_start 寫出成功回呼 → 全部分塊＋av_end 一次排入 avatar 佇列（FIFO），由 writePump 8ms 節奏送出。 */
    private final Runnable avatarChunkWriter = new Runnable() {
        @Override
        public void run() {
            if (avatarJpeg == null || avatarTotal <= 0) return;
            if (!ready || gatt == null || charCmd == null) {
                finishAvatarSession();
                return;
            }
            for (int i = avatarIdx; i < avatarTotal; i++) {
                avatarQueue.add(new WriteItem(AvatarTransfer.buildChunk(i,
                        AvatarTransfer.chunkB64(avatarJpeg, i), avatarTs), false));
            }
            avatarQueue.add(new WriteItem(AvatarTransfer.buildEnd(avatarSha, avatarTs), true));
            avatarIdx = avatarTotal;
            worker.post(writePump);
        }
    };

    private final Runnable avatarTimeout = new Runnable() {
        @Override
        public void run() {
            if (avatarJpeg != null) {
                Log.w(TAG, "avatar transfer timeout -> handleAvatarFail");
                handleAvatarFail();
            }
        }
    };

    private final Runnable avatarAckTimeout = new Runnable() {
        @Override
        public void run() {
            if (avatarJpeg != null && avatarAwaitingAck) {
                Log.w(TAG, "avatar ack timeout -> handleAvatarFail");
                handleAvatarFail();
            }
        }
    };

    private void handleAvatarFail() {
        if (avatarJpeg == null) return;
        if (avatarRetries < AvatarTransfer.RETRY_LIMIT && ready) {
            avatarRetries++;
            avatarIdx = 0;
            avatarAwaitingAck = false;
            Log.w(TAG, "avatar retry " + avatarRetries + "/" + AvatarTransfer.RETRY_LIMIT);
            avatarQueue.add(new WriteItem(AvatarTransfer.buildStart(avatarTotal, avatarJpeg.length, avatarTs), true));
            worker.post(writePump);
        } else {
            Log.w(TAG, "avatar transfer gave up");
            finishAvatarSession();
        }
    }

    // ---------- 串列化寫入（T8 聯測修正） ----------

    /**
     * 寫入序列化器（worker thread）：commandQueue 優先（start/end/missed/ping，FIFO 保序），
     * avatarQueue 其次（av_start→chunks→av_end FIFO）。writeCharacteristic 回 false 僅代表
     * GATT 忙碌（前一筆未完成）→ 放回頭 30ms 重試；連續 5 次才視為失敗。
     */
    private final Runnable writePump = new Runnable() {
        @Override
        public void run() {
            synchronized (BleCentralService.this) {
                if (stopping) {
                    commandQueue.clear();
                    avatarQueue.clear();
                    writeInFlight = false;
                    return;
                }
                if (!ready || gatt == null || charCmd == null || writeInFlight) return;
                WriteItem item = commandQueue.poll();
                if (item == null) item = avatarQueue.poll();
                if (item == null) return;
                charCmd.setWriteType(item.withResponse
                        ? BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        : BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
                if (item.withResponse) lastWritten = item.json;
                charCmd.setValue(item.json.getBytes(StandardCharsets.UTF_8));
                boolean ok;
                try {
                    ok = gatt.writeCharacteristic(charCmd);
                } catch (Exception e) {
                    ok = false;
                    Log.e(TAG, "writeCharacteristic threw", e);
                }
                if (ok) {
                    writeFalseCount = 0;
                    if (item.withResponse) {
                        writeInFlight = true; // 等 onCharacteristicWrite 回呼再 pump
                    } else {
                        worker.postDelayed(writePump, AvatarTransfer.CHUNK_PACE_MS); // NO_RESPONSE 節奏 4ms
                    }
                } else {
                    writeFalseCount++;
                    if (writeFalseCount >= 5) {
                        writeFalseCount = 0;
                        Log.w(TAG, "writeCharacteristic false x5, dropping: " + item.json);
                        String t = Command.typeOf(item.json);
                        if ("av_start".equals(t) || "av_end".equals(t) || "av_chunk".equals(t)) {
                            finishAvatarSession();
                        } else {
                            pongMisses++;
                        }
                        worker.postDelayed(writePump, 30);
                    } else {
                        // false = GATT 忙碌 → 放回頭、30ms 後重試（false ≠ 失敗）
                        if (item.withResponse) {
                            commandQueue.addFirst(item);
                        } else {
                            avatarQueue.addFirst(item);
                        }
                        worker.postDelayed(writePump, 30);
                    }
                }
            }
        }
    };

    // ---------- 心跳與重連 ----------

    private void startHeartbeat() {
        if (heartbeatStarted) return;
        heartbeatStarted = true;
        // 首筆 ping 延後一個週期再送：避開連線初期 CCCD 寫入與 GATT 佇列的競態
        // （T2 實測：連上後立即 ping 偶發 writeCharacteristic returned false，延後後即正常）
        worker.postDelayed(heartbeatTick, Constants.PING_INTERVAL_MS);
    }

    private final Runnable heartbeatTick = new Runnable() {
        @Override
        public void run() {
            if (stopping) return;
            if (ready) {
                if (LineCallListenerService.isRinging()) {
                    pongMisses = 0;
                } else {
                    pingSeq++;
                    write(Command.ping(pingSeq));
                    pongMisses++;
                    if (pongMisses >= Constants.PING_MISS_LIMIT) {
                        Log.w(TAG, "3 missed pongs -> force reconnect");
                        pongMisses = 0;
                        forceReconnect();
                    }
                }
            }
            worker.postDelayed(this, Constants.PING_INTERVAL_MS);
        }
    };

    private void forceReconnect() {
        worker.post(() -> {
            if (stopping) return;
            ready = false;
            if (gatt != null) {
                try {
                    gatt.disconnect();
                } catch (Exception ignored) {
                }
                closeGatt();
            }
            scheduleReconnect();
        });
    }

    private void scheduleReconnect() {
        if (stopping || reconnectScheduled) return;
        reconnectScheduled = true;
        int idx = Math.min(reconnectAttempt, Constants.RECONNECT_BACKOFF_MS.length - 1);
        long delay = Constants.RECONNECT_BACKOFF_MS[idx];
        reconnectAttempt = Math.min(reconnectAttempt + 1, Constants.RECONNECT_BACKOFF_MS.length - 1);
        Logs.i(TAG, "reconnect scheduled in " + delay + " ms (attempt " + reconnectAttempt + ")");
        worker.postDelayed(() -> {
            reconnectScheduled = false;
            if (stopping) return;
            startScanSafe();
        }, delay);
    }

    private synchronized void closeGatt() {
        BluetoothGatt g = gatt;
        gatt = null;
        charCmd = null;
        charState = null;
        ready = false;
        writeInFlight = false; // 連線關閉：進行中的寫入回呼可能不會再來，序列化器重設
        if (g != null) {
            try {
                g.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ---------- 藍牙開關監聽 ----------

    private final BroadcastReceiver btReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String a = intent.getAction();
            if (!BluetoothAdapter.ACTION_STATE_CHANGED.equals(a)) return;
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1);
            if (state == BluetoothAdapter.STATE_ON) {
                Logs.i(TAG, "bluetooth turned on -> start scan");
                stopping = false;
                startScanSafe();
            } else if (state == BluetoothAdapter.STATE_TURNING_OFF || state == BluetoothAdapter.STATE_OFF) {
                Logs.i(TAG, "bluetooth turning off");
                ready = false;
                stopScan();
                closeGatt();
                StatusBus.ble("已斷線", "");
            }
        }
    };
}
