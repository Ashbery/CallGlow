package com.linewatch.watch

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * v2/T8 真實頭像快取（protocol.md v2 頭像傳輸、ui-spec v3 頭像快取規則）：
 * - 記憶體：目前通話已確認顯示的頭像（bitmap）
 * - 磁碟：files/avatars/<SHA-256(name) 前 16 hex>.jpg＋SharedPreferences JSON 索引（name→file/ts）
 * - 上限 ≤10 名字、每張 ≤12KB、LRU 淘汰；CALLING 進入先查快取秒顯；未接/斷線依名字查快取
 * - 純 AOSP：BitmapShader 圓形裁切
 */
object AvatarStore {

    private const val CACHE_DIR = "avatars"
    private const val INDEX_PREFS = "avatar_index"
    private const val INDEX_KEY = "index"
    private const val MAX_ENTRIES = 10
    private const val MAX_FILE_BYTES = 12_000L

    private var appContext: Context? = null
    private var indexJson: JSONObject = JSONObject()

    /** 目前通話確認顯示的頭像（記憶體；非 name 級快取）。 */
    @Volatile
    var bitmap: Bitmap? = null
        private set

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val saved = appContext!!
            .getSharedPreferences(INDEX_PREFS, Context.MODE_PRIVATE)
            .getString(INDEX_KEY, null)
        indexJson = try {
            if (saved != null) JSONObject(saved) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }
    }

    fun set(b: Bitmap?) {
        bitmap = b
    }

    fun clear() {
        bitmap = null
    }

    /** 來電開始時載入該名字快取；命中 → 記憶體 bitmap 秒顯，回傳 true。 */
    fun loadForName(name: String): Boolean {
        bitmap = null
        val bmp = cachedBitmap(name) ?: return false
        bitmap = bmp
        return true
    }

    fun hasCached(name: String): Boolean {
        val f = cacheFile(name) ?: return false
        return f.exists() && f.length() in 1..MAX_FILE_BYTES
    }

    /** 讀取名字對應快取（未接/斷線畫面用）。 */
    fun cachedBitmap(name: String): Bitmap? {
        val f = cacheFile(name) ?: return null
        if (!f.exists() || f.length() !in 1..MAX_FILE_BYTES) return null
        return try {
            BitmapFactory.decodeFile(f.absolutePath)
        } catch (e: Exception) {
            null
        }
    }

    /** 寫入快取（av_end 驗證通過即寫，無論是否來電中）＋LRU 淘汰。 */
    fun put(name: String, bmp: Bitmap) {
        val ctx = appContext ?: return
        if (name.isBlank()) return
        val dir = File(ctx.filesDir, CACHE_DIR)
        if (!dir.exists() && !dir.mkdirs()) return
        val f = File(dir, hexName(name) + ".jpg")
        try {
            FileOutputStream(f).use { out ->
                if (!bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)) return
            }
            if (f.length() > MAX_FILE_BYTES) {
                f.delete()
                return
            }
        } catch (e: Exception) {
            return
        }
        indexJson.put(name, JSONObject().put("file", f.name).put("ts", System.currentTimeMillis()))
        evictIfNeeded()
        persistIndex()
        Protocol.logEvent(
            JSONObject().put("t", "av_cache").put("action", "put").put("sha", hexName(name)).toString()
        )
    }

    /** LRU：超過上限時刪除最久未用。 */
    private fun evictIfNeeded() {
        while (indexJson.length() > MAX_ENTRIES) {
            var oldest: String? = null
            var oldestTs = Long.MAX_VALUE
            val keys = indexJson.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val ts = indexJson.optJSONObject(k)?.optLong("ts", 0L) ?: 0L
                if (ts < oldestTs) {
                    oldestTs = ts
                    oldest = k
                }
            }
            val victim = oldest ?: break
            indexJson.remove(victim)
            cacheFile(victim)?.delete()
        }
    }

    private fun persistIndex() {
        appContext
            ?.getSharedPreferences(INDEX_PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(INDEX_KEY, indexJson.toString())
            ?.apply()
    }

    private fun cacheFile(name: String): File? {
        val ctx = appContext ?: return null
        if (name.isBlank()) return null
        return File(File(ctx.filesDir, CACHE_DIR), hexName(name) + ".jpg")
    }

    /** SHA-256(name) 前 16 hex（檔名與 log 用，不落地原始名字）。 */
    private fun hexName(name: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(name.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
            .take(16)
    }

    /** 名字首字（處理 emoji 代理對；空白 → 「?」）。 */
    fun firstCharOf(name: String): String {
        if (name.isBlank()) return "?"
        val cp = name.codePointAt(0)
        return String(Character.toChars(cp))
    }

    /** 圓形裁切為 BitmapDrawable（取原圖中央正方形，BitmapShader 畫圓）。 */
    fun circularDrawable(resources: Resources, src: Bitmap): Drawable {
        val size = minOf(src.width, src.height)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        return BitmapDrawable(resources, out)
    }
}
