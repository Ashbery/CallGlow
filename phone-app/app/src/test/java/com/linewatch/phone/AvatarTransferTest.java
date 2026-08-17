package com.linewatch.phone;

import org.junit.Test;

import java.util.Base64;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** AvatarTransfer 純邏輯單元測試（protocol v2 草案：分塊／b64／SHA-256／訊息尺寸上限）。 */
public class AvatarTransferTest {

    @Test
    public void chunkCountBoundaries() {
        assertEquals(0, AvatarTransfer.chunkCount(0));
        assertEquals(1, AvatarTransfer.chunkCount(1));
        assertEquals(1, AvatarTransfer.chunkCount(336));
        assertEquals(2, AvatarTransfer.chunkCount(337));
        assertEquals(2, AvatarTransfer.chunkCount(672));
        assertEquals(3, AvatarTransfer.chunkCount(673));
        assertEquals(36, AvatarTransfer.chunkCount(12000)); // 上限 12KB → ceil(12000/336)=36 塊
    }

    @Test
    public void chunkBytesAndB64RoundTrip() {
        byte[] jpeg = new byte[1000];
        for (int i = 0; i < jpeg.length; i++) jpeg[i] = (byte) (i * 7);
        assertEquals(336, AvatarTransfer.chunk(jpeg, 0).length);
        assertEquals(336, AvatarTransfer.chunk(jpeg, 1).length);
        assertEquals(1000 - 672, AvatarTransfer.chunk(jpeg, 2).length);
        // b64 解碼還原
        byte[] back = Base64.getDecoder().decode(AvatarTransfer.chunkB64(jpeg, 2));
        assertEquals(1000 - 672, back.length);
        for (int i = 0; i < back.length; i++) assertEquals(jpeg[672 + i], back[i]);
    }

    @Test
    public void fullChunkB64HasNoPadding() {
        // 336 = 3×112 → 完整塊 b64 無「=」補齊
        byte[] jpeg = new byte[336];
        assertTrue(!AvatarTransfer.chunkB64(jpeg, 0).contains("="));
    }

    @Test
    public void sha256KnownVector() {
        // SHA-256("abc") 標準向量
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                AvatarTransfer.sha256Hex("abc".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    public void messageFormats() {
        long ts = 1786952000123L;
        String start = AvatarTransfer.buildStart(36, 12000, ts);
        assertTrue(start.contains("\"t\":\"av_start\""));
        assertTrue(start.contains("\"total\":36"));
        assertTrue(start.contains("\"bytes\":12000"));
        assertTrue(start.contains("\"ts\":1786952000123"));

        String chunk = AvatarTransfer.buildChunk(0, "QUJD", ts);
        assertEquals("{\"t\":\"av_chunk\",\"ts\":1786952000123,\"i\":0,\"d\":\"QUJD\"}", chunk);

        String end = AvatarTransfer.buildEnd("ab".repeat(32), ts);
        assertTrue(end.startsWith("{\"t\":\"av_end\""));
        assertTrue(end.contains("\"sha\":\"" + "ab".repeat(32) + "\""));
    }

    @Test
    public void chunkMessageWithinMtuBound() {
        // 完整塊（452 b64 字元＋JSON 殼）必須 ≤ 500 bytes（MTU 517 → 寫入上限 514，留餘量）
        byte[] jpeg = new byte[12000];
        long ts = System.currentTimeMillis();
        int total = AvatarTransfer.chunkCount(jpeg.length);
        for (int i = 0; i < total; i++) {
            String msg = AvatarTransfer.buildChunk(i, AvatarTransfer.chunkB64(jpeg, i), ts);
            assertTrue("chunk " + i + " too long: " + msg.length(), msg.length() <= 500);
        }
    }

    @Test
    public void fullRoundTripSimulation() throws Exception {
        // 10KB 假 JPEG：分塊 → b64 → 依序解碼拼接 → 與原檔一致；sha 一致
        byte[] jpeg = new byte[10240];
        for (int i = 0; i < jpeg.length; i++) jpeg[i] = (byte) (i * 31 + 7);
        int total = AvatarTransfer.chunkCount(jpeg.length);
        java.io.ByteArrayOutputStream joined = new java.io.ByteArrayOutputStream();
        for (int i = 0; i < total; i++) {
            joined.write(Base64.getDecoder().decode(AvatarTransfer.chunkB64(jpeg, i)));
        }
        byte[] reassembled = joined.toByteArray();
        assertEquals(jpeg.length, reassembled.length);
        for (int i = 0; i < jpeg.length; i++) assertEquals(jpeg[i], reassembled[i]);
        assertEquals(AvatarTransfer.sha256Hex(jpeg), AvatarTransfer.sha256Hex(reassembled));
    }
}
