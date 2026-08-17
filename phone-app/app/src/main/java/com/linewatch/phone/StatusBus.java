package com.linewatch.phone;

import android.os.Handler;
import android.os.Looper;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 主執行緒狀態匯流排：BLE 狀態與通知監聽狀態（新訂閱者立即收到最後狀態重播）。 */
public final class StatusBus {

    public interface Listener {
        void onBleStatus(String state, String device);

        void onListenerStatus(boolean connected);
    }

    private static final Set<Listener> LISTENERS = ConcurrentHashMap.newKeySet();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile String lastState = "已斷線";
    private static volatile String lastDevice = "";
    private static volatile boolean lastListener = false;

    private StatusBus() {}

    public static void add(Listener l) {
        LISTENERS.add(l);
        MAIN.post(() -> {
            l.onBleStatus(lastState, lastDevice);
            l.onListenerStatus(lastListener);
        });
    }

    public static void remove(Listener l) {
        LISTENERS.remove(l);
    }

    public static void ble(String state, String device) {
        lastState = state;
        lastDevice = device == null ? "" : device;
        MAIN.post(() -> {
            for (Listener l : LISTENERS) l.onBleStatus(state, lastDevice);
        });
    }

    public static void listener(boolean connected) {
        lastListener = connected;
        MAIN.post(() -> {
            for (Listener l : LISTENERS) l.onListenerStatus(connected);
        });
    }
}
