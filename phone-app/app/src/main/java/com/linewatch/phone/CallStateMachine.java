package com.linewatch.phone;

/**
 * 來電狀態機：IDLE / RINGING（architecture.md 手機端；protocol.md v1.1）。
 * - post(call)      IDLE→RINGING，回 START(name,kind)
 * - post(missed)    RINGING→IDLE，回 END(missed=true)；
 *                   IDLE 時若未接判定窗有效 → 回 MISSED(name,kind)（補送 t="missed"）
 * - removed(key)    RINGING→IDLE，回 END(missed=false)；armMissedVerdict=true 時武裝 5s 未接判定窗
 * - ongoing()       RINGING→IDLE，回 END(missed=false)（接聽轉換，優先於來電判定）
 * - watchdog()      RINGING→IDLE，回 END(missed=true)
 * 純邏輯、執行緒安全、時間由呼叫端注入（nowMs=SystemClock.elapsedRealtime），可 JVM 單元測試。
 */
public final class CallStateMachine {

    public enum State {IDLE, RINGING}

    /** 狀態機輸出動作。 */
    public static final class Action {
        public static final int NONE = 0;
        public static final int START = 1;
        public static final int END = 2;
        /** 未接判定窗命中 → 補送 t="missed" 顯示指令（protocol.md v1.1）。 */
        public static final int MISSED = 3;

        public final int type;
        public final String name;
        public final String kind;
        public final boolean missed;

        private Action(int type, String name, String kind, boolean missed) {
            this.type = type;
            this.name = name;
            this.kind = kind;
            this.missed = missed;
        }

        public static Action none() {
            return new Action(NONE, null, null, false);
        }

        public static Action start(String name, String kind) {
            return new Action(START, name, kind, false);
        }

        public static Action end(boolean missed) {
            return new Action(END, null, null, missed);
        }

        public static Action missed(String name, String kind) {
            return new Action(MISSED, name, kind, true);
        }
    }

    private State state = State.IDLE;
    private String currentKey;
    private String lastName;
    private String lastKind;
    private final MissedVerdict verdict = new MissedVerdict();
    /** 上一次以 removed 結束通話的時刻（D 場景證據統計用：Ongoing 到達延遲）。 */
    private long lastRemovedAtMs = 0;

    public synchronized Action onCallPosted(String key, String name, String kind, boolean missed, long nowMs) {
        if (missed) {
            if (state == State.RINGING) {
                state = State.IDLE;
                currentKey = null;
                verdict.disarm();
                return Action.end(true);
            }
            // IDLE＋missed：僅未接判定窗內才顯示（防舊通知洪流，protocol.md v1.1）
            if (verdict.tryConsume(nowMs)) {
                return Action.missed(name, kind);
            }
            return Action.none();
        }
        if (state == State.RINGING) {
            if (key.equals(currentKey)) {
                return Action.none(); // 同一通的重複 posted
            }
            // 插播：以新來電取代顯示
            currentKey = key;
            lastName = name;
            lastKind = kind;
            verdict.disarm();
            return Action.start(name, kind);
        }
        state = State.RINGING;
        currentKey = key;
        lastName = name;
        lastKind = kind;
        verdict.disarm();
        return Action.start(name, kind);
    }

    public synchronized Action onCallRemoved(String key, long nowMs, boolean armMissedVerdict) {
        if (state == State.RINGING && key.equals(currentKey)) {
            state = State.IDLE;
            currentKey = null;
            lastRemovedAtMs = nowMs;
            if (armMissedVerdict) {
                verdict.arm(nowMs);
            } else {
                verdict.disarm();
            }
            return Action.end(false);
        }
        return Action.none();
    }

    /** 接聽轉換：來電通知更新為「通話中」→ 立即 end(false)。
     *  無論 RINGING/IDLE 都標記 answered（C-lite）：判定窗到期時不再補 missed。 */
    public synchronized Action onOngoing() {
        verdict.markAnswered();
        if (state == State.RINGING) {
            state = State.IDLE;
            currentKey = null;
            return Action.end(false);
        }
        return Action.none();
    }

    public synchronized Action onWatchdog() {
        if (state == State.RINGING) {
            state = State.IDLE;
            currentKey = null;
            verdict.disarm();
            return Action.end(true);
        }
        return Action.none();
    }

    public synchronized boolean isRinging() {
        return state == State.RINGING;
    }

    /** 上一次 removed 結束通話的時刻（0＝無紀錄；D 場景證據統計用）。 */
    public synchronized long getLastRemovedAtMs() {
        return lastRemovedAtMs;
    }

    /**
     * 判定窗到期（C-lite）：兩者皆無 → 回 Action.MISSED（name/kind 用響鈴時記下的值，
     * 由呼叫端補送 t="missed"）；已有 ongoing（answered）／已消費／未武裝 → Action.none()。 */
    public synchronized Action onVerdictExpired(long nowMs) {
        MissedVerdict.ExpiryDecision d = verdict.onExpiry(nowMs);
        if (d == MissedVerdict.ExpiryDecision.MISSED) {
            String n = lastName != null ? lastName : Constants.UNKNOWN_NAME;
            String k = lastKind != null ? lastKind : "voice";
            return Action.missed(n, k);
        }
        return Action.none();
    }

    public synchronized String getLastName() {
        return lastName;
    }

    public synchronized String getLastKind() {
        return lastKind;
    }
}
