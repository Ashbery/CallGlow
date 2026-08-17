package com.linewatch.phone;

import android.graphics.Bitmap;
import android.util.Log;

import java.io.ByteArrayOutputStream;

/**
 * 頭像壓縮（protocol v2 草案參數）：中央裁切正方形 → 96×96 JPEG。
 * 品質 80 → 超過 12KB 降 60 → 40 重編；仍超標則放棄（回 null，手錶沿用 T7 首字頭像）。
 */
public final class AvatarCompressor {

    private static final String TAG = "LineWatchPhone";
    public static final int SIZE = 96;
    private static final int[] QUALITIES = {80, 60, 40};

    private AvatarCompressor() {}

    /** 回 null = 無法壓到上限內（呼叫端維持首字頭像）。 */
    public static byte[] compress(Bitmap src) {
        if (src == null || src.getWidth() <= 0 || src.getHeight() <= 0) return null;
        Bitmap square = centerCropSquare(src);
        try {
            for (int q : QUALITIES) {
                byte[] jpeg = encode(square, q);
                if (jpeg != null && jpeg.length <= AvatarTransfer.MAX_JPEG_BYTES) {
                    Logs.d(TAG, "avatar compressed: " + jpeg.length + " bytes (q=" + q + ")");
                    return jpeg;
                }
                Logs.d(TAG, "avatar q=" + q + " too large: " + (jpeg == null ? -1 : jpeg.length) + " bytes");
            }
            return null;
        } finally {
            if (square != src) square.recycle();
        }
    }

    /** 中央裁切正方形（原圖可能非方形）。 */
    static Bitmap centerCropSquare(Bitmap src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int side = Math.min(w, h);
        int x = (w - side) / 2;
        int y = (h - side) / 2;
        return Bitmap.createBitmap(src, x, y, side, side);
    }

    static byte[] encode(Bitmap bmp, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!bmp.compress(Bitmap.CompressFormat.JPEG, quality, out)) return null;
        return out.toByteArray();
    }
}
