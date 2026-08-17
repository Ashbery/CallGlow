package com.linewatch.phone;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** RemovalReason 分類單元測試（常數值對照 android-34 SDK 原始碼 NotificationListenerService）。 */
public class RemovalReasonTest {

    @Test
    public void userActionsAreDetected() {
        assertTrue(RemovalReason.isUserAction(RemovalReason.CLICK));      // REASON_CLICK=1
        assertTrue(RemovalReason.isUserAction(RemovalReason.CANCEL));     // REASON_CANCEL=2
        assertTrue(RemovalReason.isUserAction(RemovalReason.CANCEL_ALL)); // REASON_CANCEL_ALL=3
    }

    @Test
    public void nonUserActionsAreNot() {
        assertFalse(RemovalReason.isUserAction(0));  // reason 未知（2-arg 委派）
        assertFalse(RemovalReason.isUserAction(8));  // REASON_APP_CANCEL
        assertFalse(RemovalReason.isUserAction(19)); // REASON_TIMEOUT
        assertFalse(RemovalReason.isUserAction(15)); // REASON_PROFILE_TURNED_OFF
    }
}
