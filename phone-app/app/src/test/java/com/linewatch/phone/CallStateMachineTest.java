package com.linewatch.phone;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** CallStateMachine 狀態轉移單元測試（architecture.md 手機端；protocol.md v1.1 未接判定窗）。 */
public class CallStateMachineTest {

    private static final long T0 = 1000L;

    private final CallStateMachine m = new CallStateMachine();

    private void ring(String key, String name, String kind, long now) {
        CallStateMachine.Action a = m.onCallPosted(key, name, kind, false, now);
        assertEquals(CallStateMachine.Action.START, a.type);
    }

    // ---- 基本路徑 ----

    @Test
    public void startFromIdle() {
        CallStateMachine.Action a = m.onCallPosted("k1", "王小明", "voice", false, T0);
        assertEquals(CallStateMachine.Action.START, a.type);
        assertEquals("王小明", a.name);
        assertEquals("voice", a.kind);
        assertTrue(m.isRinging());
    }

    @Test
    public void duplicateStartIgnored() {
        ring("k1", "王小明", "voice", T0);
        CallStateMachine.Action a = m.onCallPosted("k1", "王小明", "voice", false, T0 + 100);
        assertEquals(CallStateMachine.Action.NONE, a.type);
    }

    @Test
    public void removedEndsCallNotMissed() {
        ring("k1", "王小明", "voice", T0);
        CallStateMachine.Action a = m.onCallRemoved("k1", T0 + 1000, true);
        assertEquals(CallStateMachine.Action.END, a.type);
        assertFalse(a.missed);
        assertFalse(m.isRinging());
    }

    @Test
    public void repostMissedWhileRingingEndsAsMissed() {
        ring("k1", "王小明", "voice", T0);
        CallStateMachine.Action a = m.onCallPosted("k1", "王小明", "voice", true, T0 + 1000);
        assertEquals(CallStateMachine.Action.END, a.type);
        assertTrue(a.missed);
    }

    @Test
    public void missedWithDifferentKeyWhileRingingEndsAsMissed() {
        ring("k1", "王小明", "voice", T0);
        CallStateMachine.Action a = m.onCallPosted("k2", "王小明", "voice", true, T0 + 1000);
        assertEquals(CallStateMachine.Action.END, a.type);
        assertTrue(a.missed);
    }

    @Test
    public void watchdogEndsAsMissed() {
        ring("k1", "王小明", "voice", T0);
        CallStateMachine.Action a = m.onWatchdog();
        assertEquals(CallStateMachine.Action.END, a.type);
        assertTrue(a.missed);
        assertFalse(m.isRinging());
    }

    @Test
    public void missedWhileIdleIgnored() {
        CallStateMachine.Action a = m.onCallPosted("k1", "王小明", "voice", true, T0);
        assertEquals(CallStateMachine.Action.NONE, a.type);
        assertFalse(m.isRinging());
    }

    @Test
    public void removedWhileIdleIgnored() {
        CallStateMachine.Action a = m.onCallRemoved("k1", T0, true);
        assertEquals(CallStateMachine.Action.NONE, a.type);
    }

    @Test
    public void callWaitingReplacesCurrent() {
        ring("k1", "王小明", "voice", T0);
        CallStateMachine.Action a = m.onCallPosted("k2", "陳小美", "video", false, T0 + 100);
        assertEquals(CallStateMachine.Action.START, a.type);
        assertEquals("陳小美", a.name);
        assertEquals("video", a.kind);
    }

    @Test
    public void newCallAfterEnd() {
        ring("k1", "王小明", "voice", T0);
        m.onCallRemoved("k1", T0 + 1000, true);
        CallStateMachine.Action a = m.onCallPosted("k2", "陳小美", "voice", false, T0 + 2000);
        assertEquals(CallStateMachine.Action.START, a.type);
    }

    @Test
    public void watchdogWhileIdleIgnored() {
        CallStateMachine.Action a = m.onWatchdog();
        assertEquals(CallStateMachine.Action.NONE, a.type);
    }

    // ---- 修復 1：接聽轉換（ongoing） ----

    @Test
    public void ongoingWhileRingingEndsNotMissed() {
        ring("k1", "王小明", "voice", T0);
        CallStateMachine.Action a = m.onOngoing();
        assertEquals(CallStateMachine.Action.END, a.type);
        assertFalse(a.missed);
        assertFalse(m.isRinging());
    }

    @Test
    public void ongoingWhileIdleIgnored() {
        CallStateMachine.Action a = m.onOngoing();
        assertEquals(CallStateMachine.Action.NONE, a.type);
    }

    // ---- 修復 2：未接判定窗（protocol.md v1.1） ----

    @Test
    public void removedThenMissedWithinWindowTriggersMissedDisplay() {
        ring("k1", "王小明", "voice", T0);
        CallStateMachine.Action e = m.onCallRemoved("k1", T0 + 1000, true);
        assertEquals(CallStateMachine.Action.END, e.type);
        // 窗：T0+1000 起 5 秒 → T0+4000 在窗內
        CallStateMachine.Action a = m.onCallPosted("k2", "王小明", "voice", true, T0 + 4000);
        assertEquals(CallStateMachine.Action.MISSED, a.type);
        assertEquals("王小明", a.name);
        assertEquals("voice", a.kind);
        assertTrue(a.missed);
    }

    @Test
    public void missedBeyondWindowIgnored() {
        ring("k1", "王小明", "voice", T0);
        m.onCallRemoved("k1", T0 + 1000, true);
        // T0+7000 超出 5 秒窗
        CallStateMachine.Action a = m.onCallPosted("k2", "王小明", "voice", true, T0 + 7000);
        assertEquals(CallStateMachine.Action.NONE, a.type);
    }

    @Test
    public void missedWindowIsOneShot() {
        ring("k1", "王小明", "voice", T0);
        m.onCallRemoved("k1", T0 + 1000, true);
        CallStateMachine.Action first = m.onCallPosted("k2", "王小明", "voice", true, T0 + 4000);
        assertEquals(CallStateMachine.Action.MISSED, first.type);
        CallStateMachine.Action second = m.onCallPosted("k3", "王小明", "voice", true, T0 + 4500);
        assertEquals(CallStateMachine.Action.NONE, second.type);
    }

    @Test
    public void removedWithoutArmThenMissedIgnored() {
        // 對應 removal reason 排除（CLICK/USER_CANCEL 等使用者動作）
        ring("k1", "王小明", "voice", T0);
        m.onCallRemoved("k1", T0 + 1000, false);
        CallStateMachine.Action a = m.onCallPosted("k2", "王小明", "voice", true, T0 + 4000);
        assertEquals(CallStateMachine.Action.NONE, a.type);
    }

    @Test
    public void newRingDisarmsMissedVerdict() {
        ring("k1", "王小明", "voice", T0);
        m.onCallRemoved("k1", T0 + 1000, true);   // 武裝窗
        ring("k2", "陳小美", "voice", T0 + 2000); // 新響鈴 → 窗解除
        m.onCallRemoved("k2", T0 + 3000, false);      // 結束但不重新武裝
        CallStateMachine.Action a = m.onCallPosted("k3", "王小明", "voice", true, T0 + 3500);
        assertEquals(CallStateMachine.Action.NONE, a.type);
    }

    // ---- C-lite 三態判定窗：到期決策（captain 裁示） ----

    @Test
    public void verdictExpiryWithoutSignalDefaultsMissed() {
        ring("k1", "王小明", "voice", T0);
        m.onCallRemoved("k1", T0 + 1000, true);
        // 窗到期、無 missed、無 ongoing → 預設未接（含響鈴 name/kind）
        CallStateMachine.Action a = m.onVerdictExpired(T0 + 7000);
        assertEquals(CallStateMachine.Action.MISSED, a.type);
        assertEquals("王小明", a.name);
        assertEquals("voice", a.kind);
    }

    @Test
    public void verdictExpiryNoneAfterMissedConsumed() {
        ring("k1", "王小明", "voice", T0);
        m.onCallRemoved("k1", T0 + 1000, true);
        m.onCallPosted("k2", "王小明", "voice", true, T0 + 4000); // 窗內 missed 已補送
        assertEquals(CallStateMachine.Action.NONE, m.onVerdictExpired(T0 + 7000).type);
    }

    @Test
    public void verdictExpiryNoneAfterOngoing() {
        ring("k1", "王小明", "voice", T0);
        m.onCallRemoved("k1", T0 + 1000, true);
        m.onOngoing(); // 接聽定案 → answered 抑制
        assertEquals(CallStateMachine.Action.NONE, m.onVerdictExpired(T0 + 7000).type);
    }

    @Test
    public void verdictExpiryNoneAfterNewRing() {
        ring("k1", "王小明", "voice", T0);
        m.onCallRemoved("k1", T0 + 1000, true);
        ring("k2", "陳小美", "voice", T0 + 2000); // 新響鈴 → 窗解除
        assertEquals(CallStateMachine.Action.NONE, m.onVerdictExpired(T0 + 7000).type);
    }

    @Test
    public void verdictExpiryNoneWhenNoWindow() {
        assertEquals(CallStateMachine.Action.NONE, m.onVerdictExpired(T0).type);
    }

    @Test
    public void verdictExpiryNoneWhenRemovedWithoutArm() {
        // reason 跳過武裝（CLICK 等使用者動作）→ 無窗 → 到期無動作
        ring("k1", "王小明", "voice", T0);
        m.onCallRemoved("k1", T0 + 1000, false);
        assertEquals(CallStateMachine.Action.NONE, m.onVerdictExpired(T0 + 7000).type);
    }

    @Test
    public void ongoingDuringVerdictWindowSuppressesMissed() {
        ring("k1", "王小明", "voice", T0);
        m.onCallRemoved("k1", T0 + 1000, true);
        // removed 後 Ongoing 才到（B 場景實測序列）→ IDLE、NONE、answered 標記
        CallStateMachine.Action o = m.onOngoing();
        assertEquals(CallStateMachine.Action.NONE, o.type);
        // 窗內 missed 到達 → answered 抑制 → 不顯示
        CallStateMachine.Action a = m.onCallPosted("k2", "王小明", "voice", true, T0 + 4000);
        assertEquals(CallStateMachine.Action.NONE, a.type);
        // 到期 → 不補 missed
        assertEquals(CallStateMachine.Action.NONE, m.onVerdictExpired(T0 + 7000).type);
    }

    @Test
    public void lastRemovedAtRecorded() {
        assertEquals(0, m.getLastRemovedAtMs());
        ring("k1", "王小明", "voice", T0);
        m.onCallRemoved("k1", T0 + 1000, true);
        assertEquals(T0 + 1000, m.getLastRemovedAtMs());
    }
}
