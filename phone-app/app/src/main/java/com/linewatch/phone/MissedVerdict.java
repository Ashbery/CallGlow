package com.linewatch.phone;

/**
 * 未接判定窗（protocol.md v1.1 ＋ captain C-lite 裁示）：
 * removed→end(false) 後武裝、5 秒、一次有效。窗內三態：
 *   1. 收到未接通知 → tryConsume() 命中（補送 t="missed"）；
 *   2. 收到 Ongoing 訊號 → markAnswered() 抑制（到期不動作）；
 *   3. 到期兩者皆無 → onExpiry() 回 MISSED（預設視為未接，補送 t="missed"）。
 * 純邏輯、執行緒安全、時間由呼叫端注入。
 */
public final class MissedVerdict {

    /** 窗到期決策：NONE=未武裝/已消費；MISSED=兩者皆無預設未接；ANSWERED=已見 ongoing。 */
    public enum ExpiryDecision { NONE, MISSED, ANSWERED }

    private boolean armed = false;
    private boolean answered = false;
    private long deadlineMs = 0;

    /** 武裝：自 nowMs 起 5 秒內有效（Constants.MISSED_VERDICT_WINDOW_MS）。 */
    public synchronized void arm(long nowMs) {
        armed = true;
        answered = false;
        deadlineMs = nowMs + Constants.MISSED_VERDICT_WINDOW_MS;
    }

    /** Ongoing 訊號命中（無論 RINGING/IDLE）→ 抑制窗內 missed 與到期預設 missed。 */
    public synchronized void markAnswered() {
        answered = true;
    }

    public synchronized void disarm() {
        armed = false;
        answered = false;
        deadlineMs = 0;
    }

    /** 未接通知命中：窗內、未標記 answered、未消費過 → true 並關窗。 */
    public synchronized boolean tryConsume(long nowMs) {
        if (armed && !answered && nowMs <= deadlineMs) {
            disarm();
            return true;
        }
        if (armed && nowMs > deadlineMs) {
            disarm(); // 過期未消費 → 關窗（到期決策由 onExpiry 定）
        }
        return false;
    }

    /** 窗到期決策：未武裝→NONE；有 answered 標記→ANSWERED；其餘→MISSED（預設未接）。
     *  注意：必須先取 answered 再 disarm（disarm 會清除 answered）。 */
    public synchronized ExpiryDecision onExpiry(long nowMs) {
        if (!armed) return ExpiryDecision.NONE;
        boolean wasAnswered = answered;
        disarm();
        return wasAnswered ? ExpiryDecision.ANSWERED : ExpiryDecision.MISSED;
    }

    public synchronized boolean isArmed() {
        return armed;
    }

    public synchronized boolean isAnswered() {
        return answered;
    }
}
