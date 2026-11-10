package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Append-only JSON-Lines history (one notification per line) plus a per-app icon cache.
 * Retains notifications for a rolling 365-day window on device.
 */
object HistoryStore {
    private const val HISTORY_FILE = "notifytrace_history.jsonl"
    private const val ICON_DIR = "icons"
    private const val PREFS_NAME = "notifytrace_store_prefs"
    private const val KEY_DEMO_PURGED = "demo_purged_v1"
    const val RETENTION_MS = 365L * 24L * 60L * 60L * 1000L
    private const val MAX_RETURNED = 5000
    private const val MAX_STORED_LINES = 40000

    private val lock = Any()
    private var appendsSinceCompact = 0

    private fun file(ctx: Context) = File(ctx.filesDir, HISTORY_FILE)

    /**
     * One-time clean-up of the old built-in demo records and test pushes that earlier versions
     * wrote into the archive. Real notifications captured from other apps are never touched.
     */
    fun purgeDemoRecords(ctx: Context) {
        synchronized(lock) {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (prefs.getBoolean(KEY_DEMO_PURGED, false)) return
            val f = file(ctx)
            if (f.exists()) {
                try {
                    val demoIdPrefixes = listOf(
                        "sbn_com.security.shield_101_",
                        "sbn_com.github.android_102_",
                        "sbn_com.slack_103_",
                        "sbn_com.stripe.android_104_",
                        "sbn_android.system_105_"
                    )
                    val kept = ArrayList<String>()
                    for (line in f.readLines()) {
                        if (line.isBlank()) continue
                        try {
                            val obj = JSONObject(line)
                            val id = obj.optString("id")
                            val isDemo = demoIdPrefixes.any { id.startsWith(it) } ||
                                obj.optString("channelId") == "notifytrace_live_test_channel" ||
                                obj.optString("packageName") == ctx.packageName
                            if (!isDemo) kept.add(line)
                        } catch (e: Exception) {
                            // drop corrupt
                        }
                    }
                    f.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n"))
                } catch (e: Exception) {
                    e.printStackTrace()
                    return
                }
            }
            prefs.edit().putBoolean(KEY_DEMO_PURGED, true).apply()
        }
    }

    fun append(ctx: Context, record: JSONObject) {
        synchronized(lock) {
            file(ctx).appendText(record.toString() + "\n")
            appendsSinceCompact++
            if (appendsSinceCompact >= 500) compact(ctx)
        }
    }

    fun count(ctx: Context): Int = readRecent(ctx).length()

    /** Newest first, de-duplicated by id (later updates of a notification win), within 365 days. */
    fun readRecent(ctx: Context, limit: Int = MAX_RETURNED): JSONArray {
        val out = JSONArray()
        synchronized(lock) {
            val f = file(ctx)
            if (!f.exists()) return out
            val cutoff = System.currentTimeMillis() - RETENTION_MS
            val seen = HashSet<String>()
            val lines = try {
                f.readLines()
            } catch (e: Exception) {
                return out
            }
            for (i in lines.indices.reversed()) {
                val line = lines[i]
                if (line.isBlank()) continue
                try {
                    val obj = JSONObject(line)
                    if (obj.optLong("timestamp", 0L) < cutoff) continue
                    if (!seen.add(obj.optString("id"))) continue
                    out.put(obj)
                    if (out.length() >= limit) break
                } catch (e: Exception) {
                    // skip corrupt line
                }
            }
        }
        return out
    }

    fun deleteById(ctx: Context, targetId: String) {
        if (targetId.isBlank()) return
        synchronized(lock) {
            val f = file(ctx)
            if (!f.exists()) return
            try {
                val kept = ArrayList<String>()
                for (line in f.readLines()) {
                    if (line.isBlank()) continue
                    try {
                        val obj = JSONObject(line)
                        if (obj.optString("id") != targetId) {
                            kept.add(line)
                        }
                    } catch (e: Exception) {
                        // drop corrupt
                    }
                }
                f.writeText(if (kept.isEmpty()) "" else kept.joinToString("\n", postfix = "\n"))
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun clearAll(ctx: Context) {
        synchronized(lock) {
            val f = file(ctx)
            if (f.exists()) {
                f.writeText("")
            }
        }
    }

    /** Drop records older than 365 days and trim to the newest MAX_STORED_LINES. */
    fun compact(ctx: Context) {
        synchronized(lock) {
            appendsSinceCompact = 0
            val f = file(ctx)
            if (!f.exists()) return
            try {
                val cutoff = System.currentTimeMillis() - RETENTION_MS
                val kept = ArrayList<String>()
                for (line in f.readLines()) {
                    if (line.isBlank()) continue
                    try {
                        if (JSONObject(line).optLong("timestamp", 0L) >= cutoff) kept.add(line)
                    } catch (e: Exception) {
                        // drop corrupt
                    }
                }
                val trimmed = if (kept.size > MAX_STORED_LINES) {
                    kept.subList(kept.size - MAX_STORED_LINES, kept.size)
                } else {
                    kept
                }
                val tmp = File(ctx.filesDir, "$HISTORY_FILE.tmp")
                tmp.writeText(if (trimmed.isEmpty()) "" else trimmed.joinToString("\n", postfix = "\n"))
                if (!tmp.renameTo(f)) {
                    f.writeText(tmp.readText())
                    tmp.delete()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun iconFile(ctx: Context, pkg: String): File {
        val dir = File(ctx.filesDir, ICON_DIR)
        if (!dir.exists()) dir.mkdirs()
        return File(dir, pkg.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".txt")
    }

    fun saveIconIfMissing(ctx: Context, pkg: String, drawable: Drawable?) {
        if (drawable == null) return
        val f = iconFile(ctx, pkg)
        if (f.exists()) return
        try {
            val size = 72
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bmp)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(canvas)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 90, out)
            bmp.recycle()
            f.writeText("data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP))
        } catch (e: Exception) {
            // no icon is fine
        }
    }

    /** { packageName: dataUri } for every app seen so far. */
    fun readIconMap(ctx: Context): JSONObject {
        val map = JSONObject()
        val dir = File(ctx.filesDir, ICON_DIR)
        val files = dir.listFiles() ?: return map
        for (f in files) {
            try {
                map.put(f.name.removeSuffix(".txt"), f.readText())
            } catch (e: Exception) {
                // skip
            }
        }
        return map
    }
}
