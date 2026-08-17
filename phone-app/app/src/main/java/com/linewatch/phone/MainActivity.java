package com.linewatch.phone;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;

/**
 * 設定頁（ui-spec.md 手機端）：狀態列、總開關、測試按鈕（直送 BLE）、通知存取跳轉、藍牙權限請求。
 * logcat tag：LineWatchPhone（test-plan §0）。
 */
public class MainActivity extends AppCompatActivity implements StatusBus.Listener {

    private static final String TAG = "LineWatchPhone";

    private TextView tvStatus;
    private TextView tvListener;
    private SwitchCompat swEnable;

    private final Handler main = new Handler(Looper.getMainLooper());
    /** 測試來電按鈕的 90s 看門狗（test-plan §5.3）。 */
    private final Runnable testWatchdog = () -> {
        Log.w(TAG, "test watchdog fired -> " + Command.end(true));
        BleCentralService.send(Command.end(true));
    };

    private ActivityResultLauncher<String[]> permLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tvStatus = findViewById(R.id.tvStatus);
        tvListener = findViewById(R.id.tvListener);
        swEnable = findViewById(R.id.swEnable);
        MaterialButton btnTestCall = findViewById(R.id.btnTestCall);
        MaterialButton btnTestMissed = findViewById(R.id.btnTestMissed);
        MaterialButton btnStopTest = findViewById(R.id.btnStopTest);
        MaterialButton btnNotifAccess = findViewById(R.id.btnNotifAccess);

        swEnable.setChecked(Prefs.isEnabled(this));
        swEnable.setOnCheckedChangeListener((buttonView, isChecked) -> onToggle(isChecked));

        btnTestCall.setOnClickListener(v -> {
            String cmd = Command.start("測試來電", "voice");
            BleCentralService.send(cmd);
            Logs.i(TAG, "test call sent: " + cmd);
            main.removeCallbacks(testWatchdog);
            main.postDelayed(testWatchdog, Constants.WATCHDOG_MS);
        });
        btnTestMissed.setOnClickListener(v -> {
            main.removeCallbacks(testWatchdog);
            String cmd = Command.end(true);
            BleCentralService.send(cmd);
            Logs.i(TAG, "test missed sent: " + cmd);
        });
        btnStopTest.setOnClickListener(v -> {
            main.removeCallbacks(testWatchdog);
            String cmd = Command.end(false);
            BleCentralService.send(cmd);
            Logs.i(TAG, "test stop sent: " + cmd);
        });
        btnNotifAccess.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));

        permLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                    boolean ok = true;
                    for (String p : requiredPermissions()) {
                        if (Boolean.FALSE.equals(result.get(p))) ok = false;
                    }
                    if (ok) {
                        startBleService();
                    } else {
                        Toast.makeText(this, R.string.toast_perm_denied, Toast.LENGTH_LONG).show();
                        Prefs.setEnabled(this, false);
                        swEnable.setChecked(false);
                    }
                });
    }

    @Override
    protected void onStart() {
        super.onStart();
        StatusBus.add(this);
    }

    @Override
    protected void onStop() {
        StatusBus.remove(this);
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Prefs.isEnabled(this) && !BleCentralService.isRunning()) {
            startBleService();
        }
    }

    // ---------- StatusBus.Listener ----------

    @Override
    public void onBleStatus(String state, String device) {
        String text = getString(R.string.ble_status, state);
        if (device != null && !device.isEmpty()) {
            text += "（手錶 " + device + "）";
        }
        tvStatus.setText(text);
    }

    @Override
    public void onListenerStatus(boolean connected) {
        tvListener.setText(getString(R.string.listener_status,
                connected ? getString(R.string.granted) : getString(R.string.not_granted)));
        tvListener.setTextColor(ContextCompat.getColor(this,
                connected ? R.color.line_green : R.color.warn));
    }

    // ---------- 內部 ----------

    private void onToggle(boolean checked) {
        if (checked) {
            Prefs.setEnabled(this, true);
            boolean allGranted = true;
            for (String p : requiredPermissions()) {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (!allGranted) {
                String[] perms = requiredPermissions();
                if (perms.length > 0) permLauncher.launch(perms);
                return;
            }
            startBleService();
        } else {
            Prefs.setEnabled(this, false);
            main.removeCallbacks(testWatchdog);
            if (LineCallListenerService.isRinging()) {
                BleCentralService.send(Command.end(true));
            }
            stopService(new Intent(this, BleCentralService.class));
        }
    }

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.POST_NOTIFICATIONS};
        }
        if (Build.VERSION.SDK_INT >= 31) {
            return new String[]{
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT};
        }
        return new String[]{};
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
