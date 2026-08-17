package com.linewatch.phone;

import android.app.Notification;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

/**
 * 監聽 LINE（jp.naver.line.android）來電通知 → CallParser → CallStateMachine → BLE 指令。
 * 規格：docs/architecture.md 手機端、docs/protocol.md（v1.1 含 t="missed" 未接判定窗）。
 * logcat tag：LineWatchPhone（test-plan §0）。
 */
public class LineCallListenerService extends NotificationListenerService {

    private static final String TAG = "LineWatchPhone";
    private static volatile LineCallListenerService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final CallStateMachine machine = new CallStateMachine();

    /** 指令實際寫出狀態（BLE 重連後補送同步用）。 */
    private boolean startWritten = false;
    private boolean endWritten = false;
    /** 最近一次 removed 的 reason（判定窗證據 log 用）。 */
    private int lastRemovalReason = -1;
    /** 頭像壓縮單執行緒（daemon，不阻塞通知回呼）。 */
    private final java.util.concurrent.ExecutorService avatarExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "avatar-compress");
                t.setDaemon(true);
                return t;
            });

    private final Runnable watchdog = () -> {
        if (instance != this) return; // 殭屍計時器防護：listener 重綁後舊實例的排程一律拋棄
        CallStateMachine.Action a = machine.onWatchdog();
        if (a.type == CallStateMachine.Action.END) {
            Log.w(TAG, "watchdog fired -> " + Command.end(true));
            sendEnd(true);
        }
    };

    /** 未接判定窗到期（C-lite）：兩者皆無 → 補送 t="missed"（預設未接）；否則不動作。都留 DEBUG 證據 log。 */
    private final Runnable verdictExpiry = () -> {
        if (instance != this) return; // 殭屍計時器防護：同上
        long now = SystemClock.elapsedRealtime();
        long removedAt = machine.getLastRemovedAtMs();
        long delay = removedAt > 0 ? now - removedAt : -1;
        CallStateMachine.Action a = machine.onVerdictExpired(now);
        if (a.type == CallStateMachine.Action.MISSED) {
            Logs.d(TAG, "verdict window expired -> default missed reason=" + lastRemovalReason
                    + " delay=" + delay + "ms -> " + Command.missed(a.name, a.kind));
            BleCentralService.abortAvatar();
            BleCentralService.send(Command.missed(a.name, a.kind));
        } else {
            Logs.d(TAG, "verdict window expired -> no action (answered/consumed/disarmed) reason="
                    + lastRemovalReason + " delay=" + delay + "ms");
        }
    };

    public static boolean isRunning() {
        return instance != null;
    }

    public static boolean isRinging() {
        LineCallListenerService s = instance;
        return s != null && s.machine.isRinging();
    }

    /** BLE 連線就緒（含重連）後由 BleCentralService 呼叫：補送同步指令。 */
    public static void notifyBleReady() {
        LineCallListenerService s = instance;
        if (s != null) s.onBleReady();
    }

    /** 手機→手錶指令寫出成功後由 BleCentralService 呼叫。 */
    public static void notifyCommandWritten(String json) {
        LineCallListenerService s = instance;
        if (s != null) s.onCommandWritten(json);
    }

    // ---------- 生命週期 ----------

    @Override
    public void onListenerConnected() {
        instance = this;
        StatusBus.listener(true);
        Logs.i(TAG, "notification listener connected");
        if (Prefs.isEnabled(this)) {
            startBleService();
        }
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        StatusBus.listener(false);
        Log.w(TAG, "notification listener disconnected (access revoked?)");
        // 重綁防護：本實例的計時器全部取消（主 looper 是共用的，不清會變殭屍排程）；
        // 頭像 session 屬 BleCentralService 層，中止避免半截傳輸。
        main.removeCallbacks(watchdog);
        main.removeCallbacks(verdictExpiry);
        BleCentralService.abortAvatar();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn, NotificationListenerService.RankingMap rankingMap) {
        try {
            if (!Constants.LINE_PACKAGE.equals(sbn.getPackageName())) return;
            if (!Prefs.isEnabled(this)) return;

            Notification n = sbn.getNotification();
            Bundle e = n.extras;
            CharSequence titleCs = e == null ? null : e.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence subCs = e == null ? null : e.getCharSequence(Notification.EXTRA_SUB_TEXT);
            CharSequence textCs = e == null ? null : e.getCharSequence(Notification.EXTRA_TEXT);
            if (textCs == null && e != null) {
                CharSequence[] lines = e.getCharSequenceArray(Notification.EXTRA_TEXT_LINES);
                if (lines != null && lines.length > 0) textCs = lines[0];
            }
            String title = titleCs == null ? "" : titleCs.toString();
            String sub = subCs == null ? "" : subCs.toString();
            String text = textCs == null ? "" : textCs.toString();
            String category = n.category == null ? "" : n.category;
            String channelId = n.getChannelId() == null ? "" : n.getChannelId();
            long now = SystemClock.elapsedRealtime();

            // 證據收集：所有 LINE 通知一行 debug log（test-plan T4 定位用）
            // 頭像偵測（v2 頭像方案評估）：通知可能夾帶來電者頭像（largeIcon / EXTRA_PICTURE）
            android.graphics.Bitmap largeIcon = null;
            try {
                android.graphics.drawable.Icon li = n.getLargeIcon();
                if (li != null) {
                    android.graphics.drawable.Drawable d = li.loadDrawable(this);
                    if (d instanceof android.graphics.drawable.BitmapDrawable) {
                        largeIcon = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
                    }
                }
            } catch (Exception ignore) { }
            android.graphics.Bitmap pic = null;
            try { pic = (android.graphics.Bitmap) e.getParcelable(Notification.EXTRA_PICTURE); } catch (Exception ignore) { }
            Logs.d(TAG, "LINE notify: key=" + sbn.getKey() + " channel=" + channelId
                    + " category=" + category + " title=" + title + " sub=" + sub + " text=" + text
                    + " largeIcon=" + (largeIcon == null ? "null" : largeIcon.getWidth() + "x" + largeIcon.getHeight())
                    + " pic=" + (pic == null ? "null" : pic.getWidth() + "x" + pic.getHeight()));

            // 修復 1：接聽轉換（通話中頻道/文字）→ 立即 end(false)，優先於來電判定
            if (CallParser.isOngoing(channelId, title, sub, text)) {
                CallStateMachine.Action a = machine.onOngoing();
                if (a.type == CallStateMachine.Action.END) {
                    Logs.i(TAG, "ongoing detected -> " + Command.end(false));
                    sendEnd(false);
                } else {
                    // IDLE（removed 後 Ongoing 才到＝接聽定案）：記錄 Ongoing 延遲供 D 場景統計
                    long removedAt = machine.getLastRemovedAtMs();
                    String delay = removedAt > 0 ? (now - removedAt) + "ms since removed" : "no recent removed";
                    Logs.d(TAG, "ongoing while idle (answered, verdict settled): " + delay);
                }
                return;
            }

            boolean missed = CallParser.isMissed(title, sub, text);
            String name = CallParser.name(title, sub, text);
            String kind = CallParser.kind(title, sub, text);

            if (missed) {
                CallStateMachine.Action a = machine.onCallPosted(sbn.getKey(), name, kind, true, now);
                if (a.type == CallStateMachine.Action.END) {
                    Logs.i(TAG, "call end (missed repost) -> " + Command.end(true));
                    sendEnd(true);
                } else if (a.type == CallStateMachine.Action.MISSED) {
                    // 未接判定窗命中 → 補送顯示指令（protocol.md v1.1）
                    if (Constants.UNKNOWN_NAME.equals(a.name) && machine.getLastName() != null) {
                        name = machine.getLastName();
                    }
                    Logs.i(TAG, "missed verdict window hit -> " + Command.missed(name, a.kind));
                    BleCentralService.abortAvatar();
                    BleCentralService.send(Command.missed(name, a.kind));
                } else {
                    Logs.d(TAG, "missed while idle outside window, ignored: title=" + title + " text=" + text);
                }
                return;
            }

            if (!CallParser.isCall(category, title, sub, text)) return;

            CallStateMachine.Action a = machine.onCallPosted(sbn.getKey(), name, kind, false, now);
            if (a.type == CallStateMachine.Action.START) {
                Logs.i(TAG, "call start: " + Command.start(name, kind));
                BleCentralService.abortAvatar(); // 新來電取代舊頭像 session
                startBleService();
                BleCentralService.send(Command.start(name, kind));
                main.removeCallbacks(watchdog);
                main.removeCallbacks(verdictExpiry);
                main.postDelayed(watchdog, Constants.WATCHDOG_MS);
                // 頭像（protocol v2）：start 已先送出（震動立即），壓縮後後台分塊傳送
                final android.graphics.Bitmap avatar = largeIcon != null ? largeIcon : pic;
                if (avatar != null) {
                    avatarExecutor.execute(() -> {
                        byte[] jpeg = AvatarCompressor.compress(avatar);
                        if (jpeg != null) {
                            BleCentralService.sendAvatar(jpeg);
                        } else {
                            Logs.d(TAG, "avatar compress gave up (too large) -> keep initial avatar");
                        }
                    });
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "onNotificationPosted failed", ex);
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, NotificationListenerService.RankingMap rankingMap) {
        // 相容委派：極少數裝置可能仍呼叫 2-arg 版本（reason 未知 → 0，仍會武裝判定窗）
        onNotificationRemoved(sbn, rankingMap, 0);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn, NotificationListenerService.RankingMap rankingMap, int reason) {
        try {
            if (!Constants.LINE_PACKAGE.equals(sbn.getPackageName())) return;
            lastRemovalReason = reason;
            Logs.d(TAG, "LINE removed: key=" + sbn.getKey() + " reason=" + reason);
            long now = SystemClock.elapsedRealtime();
            // 選配細化：使用者動作類 reason（點擊/使用者清除）→ 不武裝未接判定窗（多半是接聽或拒接）
            boolean armVerdict = !isUserActionReason(reason);
            CallStateMachine.Action a = machine.onCallRemoved(sbn.getKey(), now, armVerdict);
            if (a.type == CallStateMachine.Action.END) {
                Logs.i(TAG, "notification removed -> " + Command.end(false) + " (armVerdict=" + armVerdict + ")");
                sendEnd(false);
                if (armVerdict) {
                    main.removeCallbacks(verdictExpiry);
                    main.postDelayed(verdictExpiry, Constants.MISSED_VERDICT_WINDOW_MS);
                }
            }
        } catch (Exception ex) {
            Log.e(TAG, "onNotificationRemoved failed", ex);
        }
    }

    /** removal reason 分類：使用者動作類 → 不武裝未接判定窗（protocol.md v1.1 選配細化）。
     *  常數值已對照 android-34 SDK 原始碼（NotificationListenerService）：
     *  REASON_CLICK=1／REASON_CANCEL=2（使用者關閉單則）／REASON_CANCEL_ALL=3（清除全部）。
     *  分類邏輯在純邏輯類 RemovalReason（JVM 可測）。 */
    private static boolean isUserActionReason(int reason) {
        return RemovalReason.isUserAction(reason);
    }

    // ---------- 內部 ----------

    private void sendEnd(boolean missed) {
        main.removeCallbacks(watchdog);
        main.removeCallbacks(verdictExpiry);
        BleCentralService.abortAvatar(); // 通話結束 → 中止頭像傳輸
        BleCentralService.send(Command.end(missed));
    }

    private void onBleReady() {
        if (machine.isRinging()) {
            Logs.i(TAG, "ble ready while ringing -> resend " + Command.start(machine.getLastName(), machine.getLastKind()));
            BleCentralService.send(Command.start(machine.getLastName(), machine.getLastKind()));
        } else if (startWritten && !endWritten) {
            Logs.i(TAG, "ble ready, idle with unsent end -> " + Command.end(true));
            BleCentralService.send(Command.end(true));
        }
    }

    private void onCommandWritten(String json) {
        String t = Command.typeOf(json);
        if ("start".equals(t)) {
            startWritten = true;
            endWritten = false;
        } else if ("end".equals(t)) {
            endWritten = true;
            startWritten = false;
        }
    }

    private void startBleService() {
        Intent i = new Intent(this, BleCentralService.class).setAction(Constants.ACTION_START);
        try {
            startForegroundService(i);
        } catch (Exception ex) {
            Log.w(TAG, "cannot start BLE service: " + ex);
        }
    }
}
