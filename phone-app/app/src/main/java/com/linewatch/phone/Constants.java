package com.linewatch.phone;

import java.util.UUID;

/** 專案常數（與 docs/protocol.md、docs/decisions.md 一致；改動需同步文件）。 */
public final class Constants {

    private Constants() {}

    /** 監聽的 LINE 套件。 */
    public static final String LINE_PACKAGE = "jp.naver.line.android";

    /** android.app.Notification.CATEGORY_CALL 的值（複製於此，供 JVM 單元測試）。 */
    public static final String CATEGORY_CALL = "call";

    /** LINE 通話通知頻道（T4 實測 dumpsys）：來電 VoIP.01.Incoming；接聽後通話中 VoIP.02.Ongoing。 */
    public static final String LINE_CHANNEL_INCOMING = "voip.01.incoming";
    public static final String LINE_CHANNEL_ONGOING = "voip.02.ongoing";

    /** Nordic UART Service UUID（protocol.md）。 */
    public static final UUID UUID_SERVICE = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E");
    public static final UUID UUID_CHAR_CMD = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E");
    public static final UUID UUID_CHAR_STATE = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E");
    public static final UUID UUID_CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

    /** 手機端看門狗：RINGING 後 90s 無結束事件 → end(missed=true)。 */
    public static final long WATCHDOG_MS = 90_000L;

    /** 未接判定窗（protocol.md v1.1）：removed→end(false) 後武裝、一次有效、5 秒。 */
    public static final long MISSED_VERDICT_WINDOW_MS = 5_000L;

    /** D18 正式版：關閉常態日誌（開發/除錯時改 true）。Log.w/Log.e 不受此開關控制。 */
    public static final boolean LOG_ENABLED = false;

    /** 心跳：空閒時每 10s ping；連續 3 次無 pong → 重連。 */
    public static final long PING_INTERVAL_MS = 10_000L;
    public static final int PING_MISS_LIMIT = 3;

    /** 重連 backoff：5s → 15s → 30s（封頂）。 */
    public static final long[] RECONNECT_BACKOFF_MS = {5_000L, 15_000L, 30_000L};

    /** BLE 參數與訊息上限。 */
    public static final int MTU_REQUEST = 247;
    public static final int MAX_NAME_BYTES = 60;
    public static final int MAX_MSG_BYTES = 200;

    /** 名稱解析 fallback。 */
    public static final String UNKNOWN_NAME = "未知聯絡人";

    /** Service intent actions。 */
    public static final String ACTION_START = "com.linewatch.phone.action.START";
    public static final String ACTION_STOP = "com.linewatch.phone.action.STOP";

    /** 前台服務通知。 */
    public static final String CHANNEL_BLE = "linewatch_ble";
    public static final int NOTIFICATION_ID = 1001;
}
