package com.linewatch.phone;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

/**
 * 真實頭像傳輸純邏輯（protocol v2 草案）：分塊／base64／SHA-256／JSON 訊息建構。
 * 無 Android 相依（訊息用手拼 JSON，b64 與 hex 皆 JSON 安全字元），可 JVM 單元測試。
 * 參數：96×96 JPEG ≤12KB；每塊 336 bytes（b64 448 字元，整條訊息 ≈498 ≤500 bytes ≤ MTU-3=514）。
 * 註：早期 339B 版訊息實際 501 bytes 略超 500（watch-engineer 審查發現）→ 定 336B。
 */
public final class AvatarTransfer {

    public static final int CHUNK_RAW_BYTES = 336; // 336B→b64 448 字元＋JSON 殼（ts 13 位數）≈498 ≤500
    public static final int MAX_JPEG_BYTES = 12_000;
    public static final long TIMEOUT_MS = 5_000L;
    public static final long ACK_TIMEOUT_MS = 2_000L;
    public static final int RETRY_LIMIT = 1;
    /** v1.0.1：BLE 未就緒時，頭像等就緒重送的上限時間（超過即放棄；來電前段重連通常 <3s）。 */
    public static final long READY_WAIT_MS = 15_000L;
    /** chunk 送出節奏（NO_RESPONSE 排程間距；captain 配合調整 8ms→4ms 降低首次傳輸延遲）。 */
    public static final long CHUNK_PACE_MS = 4;

    private AvatarTransfer() {}

    public static int chunkCount(int bytes) {
        if (bytes <= 0) return 0;
        return (bytes + CHUNK_RAW_BYTES - 1) / CHUNK_RAW_BYTES;
    }

    /** 第 idx 塊的原始位元組（idx 0 起，末塊可能較短）。 */
    public static byte[] chunk(byte[] jpeg, int idx) {
        int start = idx * CHUNK_RAW_BYTES;
        if (start >= jpeg.length) return new byte[0];
        int end = Math.min(start + CHUNK_RAW_BYTES, jpeg.length);
        byte[] out = new byte[end - start];
        System.arraycopy(jpeg, start, out, 0, end - start);
        return out;
    }

    public static String chunkB64(byte[] jpeg, int idx) {
        return Base64.getEncoder().encodeToString(chunk(jpeg, idx));
    }

    /** SHA-256（原始 JPEG 位元組）→ 64 hex。 */
    public static String sha256Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(data);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String buildStart(int totalChunks, int totalBytes, long ts) {
        return "{\"t\":\"av_start\",\"ts\":" + ts + ",\"total\":" + totalChunks
                + ",\"bytes\":" + totalBytes + "}";
    }

    public static String buildChunk(int idx, String b64, long ts) {
        return "{\"t\":\"av_chunk\",\"ts\":" + ts + ",\"i\":" + idx
                + ",\"d\":\"" + b64 + "\"}";
    }

    public static String buildEnd(String shaHex, long ts) {
        return "{\"t\":\"av_end\",\"ts\":" + ts + ",\"sha\":\"" + shaHex + "\"}";
    }
}
