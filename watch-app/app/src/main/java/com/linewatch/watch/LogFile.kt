package com.linewatch.watch

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v1.0.2：裝置端檔案日誌（logcat 之外的第二通道；logcat 緩衝短、重開機即清空）。
 * - 位置：<externalFilesDir>/logs/yyyyMMdd.log
 *   → /sdcard/Android/data/com.linewatch.watch/files/logs/（adb pull 可取回）
 * - 保留：3 天（逾齡檔案自動刪除）；總量安全上限 8MB（超過從最舊刪）。
 * - 內容：Protocol.logEvent/logI/logD 全部寫入（不受 setprop 開關影響；僅存本機）。
 * - 失敗不影響主流程（靜默）。
 */
object LogFile {

    private const val DIR = "logs"
    private const val RETENTION_MS = 3L * 24 * 60 * 60 * 1000
    private const val MAX_TOTAL_BYTES = 8L * 1024 * 1024

    @Volatile
    private var dir: File? = null

    fun init(context: Context) {
        if (dir != null) return
        dir = context.getExternalFilesDir(null)?.let { File(it, DIR) }
        prune()
    }

    fun write(level: String, message: String) {
        val d = dir ?: return
        try {
            if (!d.exists() && !d.mkdirs()) return
            val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val day = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
            val f = File(d, "$day.log")
            FileOutputStream(f, true).use { out ->
                out.write(("[$level] $stamp $message\n").toByteArray(Charsets.UTF_8))
            }
            prune()
        } catch (e: Exception) {
            // 日誌失敗不影響主流程
        }
    }

    /** 逾齡刪除＋總量超標從最舊刪。 */
    private fun prune() {
        val d = dir ?: return
        try {
            val files = d.listFiles()?.toMutableList() ?: return
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            files.sortBy { it.lastModified() }
            for (f in files) {
                if (f.lastModified() < cutoff) {
                    f.delete()
                }
            }
            var total = files.filter { it.exists() }.sumOf { it.length() }
            var i = 0
            while (total > MAX_TOTAL_BYTES && i < files.size) {
                val f = files[i++]
                if (f.exists()) {
                    val s = f.length()
                    if (f.delete()) total -= s
                }
            }
        } catch (e: Exception) {
        }
    }
}
