package com.linewatch.phone;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** MissedVerdict 未接判定窗單元測試（protocol.md v1.1＋C-lite：5 秒、一次有效、answered 抑制、到期預設未接）。 */
public class MissedVerdictTest {

    @Test
    public void consumeWithinWindowOnce() {
        MissedVerdict v = new MissedVerdict();
        v.arm(1000L); // 窗：1000~6000
        assertTrue(v.isArmed());
        assertTrue(v.tryConsume(5000L));
        assertFalse(v.isArmed());
        assertFalse(v.tryConsume(5500L)); // 一次有效
    }

    @Test
    public void beyondWindowFails() {
        MissedVerdict v = new MissedVerdict();
        v.arm(1000L);
        assertFalse(v.tryConsume(6001L));
        assertFalse(v.isArmed());
    }

    @Test
    public void disarmPreventsConsume() {
        MissedVerdict v = new MissedVerdict();
        v.arm(1000L);
        v.disarm();
        assertFalse(v.tryConsume(2000L));
    }

    @Test
    public void rearmWorksAfterExpiry() {
        MissedVerdict v = new MissedVerdict();
        v.arm(1000L);
        assertFalse(v.tryConsume(7000L)); // 超窗消費失敗並關窗
        v.arm(8000L);                    // 重新武裝
        assertTrue(v.tryConsume(12000L));
    }

    @Test
    public void notArmedInitially() {
        MissedVerdict v = new MissedVerdict();
        assertFalse(v.isArmed());
        assertFalse(v.tryConsume(1000L));
    }

    // ---- C-lite：answered 抑制與到期決策 ----

    @Test
    public void answeredSuppressesConsume() {
        MissedVerdict v = new MissedVerdict();
        v.arm(1000L);
        v.markAnswered();
        assertTrue(v.isAnswered());
        assertFalse(v.tryConsume(3000L)); // 窗內 missed 被抑制
        assertEquals(MissedVerdict.ExpiryDecision.ANSWERED, v.onExpiry(7000L));
    }

    @Test
    public void expiryDefaultsMissed() {
        MissedVerdict v = new MissedVerdict();
        v.arm(1000L);
        assertEquals(MissedVerdict.ExpiryDecision.MISSED, v.onExpiry(6000L));
        assertFalse(v.isArmed());
    }

    @Test
    public void expiryAnsweredAfterMark() {
        MissedVerdict v = new MissedVerdict();
        v.arm(1000L);
        v.markAnswered();
        assertEquals(MissedVerdict.ExpiryDecision.ANSWERED, v.onExpiry(6000L));
    }

    @Test
    public void expiryNoneWhenUnarmed() {
        MissedVerdict v = new MissedVerdict();
        assertEquals(MissedVerdict.ExpiryDecision.NONE, v.onExpiry(1000L));
    }

    @Test
    public void armResetsAnswered() {
        MissedVerdict v = new MissedVerdict();
        v.arm(1000L);
        v.markAnswered();
        v.arm(2000L); // 重新武裝 → answered 歸零
        assertFalse(v.isAnswered());
        assertEquals(MissedVerdict.ExpiryDecision.MISSED, v.onExpiry(8000L));
    }
}
