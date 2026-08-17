package com.linewatch.phone;

/**
 * NotificationListenerService removal reason 分類（純邏輯，JVM 可測）。
 * 常數值對照 android-34 SDK 原始碼 android.service.notification.NotificationListenerService：
 *   REASON_CLICK=1（使用者點擊）、REASON_CANCEL=2（使用者關閉單則）、
 *   REASON_CANCEL_ALL=3（使用者清除全部通知）。
 * 使用者動作類 reason → 來電多半已被接聽/拒接 → 不武裝未接判定窗（protocol.md v1.1 選配細化）。
 */
public final class RemovalReason {

    /** NotificationListenerService.REASON_CLICK = 1 */
    public static final int CLICK = 1;
    /** NotificationListenerService.REASON_CANCEL = 2 */
    public static final int CANCEL = 2;
    /** NotificationListenerService.REASON_CANCEL_ALL = 3 */
    public static final int CANCEL_ALL = 3;

    private RemovalReason() {}

    public static boolean isUserAction(int reason) {
        return reason == CLICK || reason == CANCEL || reason == CANCEL_ALL;
    }
}
