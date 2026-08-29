package com.linewatch.phone;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.Locale;

/**
 * v1.0.2：裝置端檔案日誌（與手錶端 LogFile 同規格）。
 * - 位置：<externalFilesDir>/logs/yyyyMMdd.log
 *   → /sdcard/Android/data/com.linewatch.phone/files/logs/（adb pull 可取回）
 * - 保留：3 天（逾齡自動刪除）；總量安全上限 8MB（超過從最舊刪）。
 * - Logs.i/d 一律寫入檔案（不受 setprop 開關影響；僅存本機）。
 */
public final class LogFile {

    private static final String DIR = "logs";
    private static final long RETENTION_MS = 3L * 24 * 60 * 60 * 1000;
    private static final long MAX_TOTAL_BYTES = 8L * 1024 * 1024;
    private static volatile File dir;

    private LogFile() {}

    public static void init(Context ctx) {
        if (dir != null) return;
        File ext = ctx.getExternalFilesDir(null);
        if (ext == null) return;
        dir = new File(ext, DIR);
        prune();
    }

    public static void write(String level, String tag, String msg) {
        File d = dir;
        if (d == null) return;
        try {
            if (!d.exists() && !d.mkdirs()) return;
            String stamp = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
            String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
            File f = new File(d, day + ".log");
            FileOutputStream out = new FileOutputStream(f, true);
            try {
                out.write(("[" + level + "] " + stamp + " " + tag + " " + msg + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            prune();
        } catch (Exception e) {
            // 日誌失敗不影響主流程
        }
    }

    private static void prune() {
        File d = dir;
        if (d == null) return;
        try {
            File[] files = d.listFiles();
            if (files == null) return;
            long cutoff = System.currentTimeMillis() - RETENTION_MS;
            for (File f : files) {
                if (f.lastModified() < cutoff) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete();
                }
            }
            files = d.listFiles();
            if (files == null) return;
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));
            long total = 0;
            for (File f : files) total += f.length();
            for (File f : files) {
                if (total <= MAX_TOTAL_BYTES) break;
                long s = f.length();
                if (f.delete()) total -= s;
            }
        } catch (Exception e) {
        }
    }
}
