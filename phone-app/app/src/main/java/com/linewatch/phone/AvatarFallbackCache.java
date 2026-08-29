package com.linewatch.phone;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;

/**
 * v1.0.2：手機端「上次成功頭像」備援快取。
 * 目的：LINE 通知偶發不附 largeIcon／頭像整天讀取失敗時，用該名字上次成功送出的 JPEG 補送，
 * 讓手錶仍能顯示真頭像（鍵＝截斷後名字，與手錶端快取鍵一致；同名聯絡人共用一格，同手錶端限制）。
 * - 位置：filesDir/avatar_last/<sha16(name)>.jpg
 * - 上限：20 個名字（LRU by lastModified；寫入即刷新）。
 */
public final class AvatarFallbackCache {

    private static final String DIR = "avatar_last";
    private static final int MAX_ENTRIES = 20;
    private static File dir;

    private AvatarFallbackCache() {}

    public static void init(Context ctx) {
        if (dir != null) return;
        dir = new File(ctx.getFilesDir(), DIR);
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        evictIfNeeded();
    }

    public static byte[] get(String name) {
        File f = fileFor(name);
        if (f == null || !f.exists()) return null;
        try {
            return java.nio.file.Files.readAllBytes(f.toPath());
        } catch (Exception e) {
            return null;
        }
    }

    public static void put(String name, byte[] jpeg) {
        File f = fileFor(name);
        if (f == null || jpeg == null || jpeg.length == 0) return;
        try {
            FileOutputStream out = new FileOutputStream(f);
            try {
                out.write(jpeg);
            } finally {
                out.close();
            }
        } catch (Exception e) {
            return;
        }
        evictIfNeeded();
    }

    private static File fileFor(String name) {
        if (dir == null || name == null || name.isEmpty()) return null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(name.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(16);
            for (int i = 0; i < 8; i++) sb.append(String.format("%02x", d[i]));
            return new File(dir, sb + ".jpg");
        } catch (Exception e) {
            return null;
        }
    }

    private static void evictIfNeeded() {
        if (dir == null) return;
        File[] files = dir.listFiles();
        if (files == null || files.length <= MAX_ENTRIES) return;
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - MAX_ENTRIES; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }
}
