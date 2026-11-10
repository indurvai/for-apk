package com.example

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import org.json.JSONArray
import org.json.JSONObject

class NotifyTraceListenerService : NotificationListenerService() {
    companion object {
        @Volatile
        var onNewNotificationCallback: ((String) -> Unit)? = null

        // key -> fingerprint of the last stored version, so repeated identical updates are not logged
        private val lastSeen = object : LinkedHashMap<String, String>(256, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 500
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        HistoryStore.compact(applicationContext)
        // Capture everything currently active in the notification shade the moment access is granted
        try {
            activeNotifications?.forEach { handle(it, live = false) }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        try {
            requestRebind(ComponentName(this, NotifyTraceListenerService::class.java))
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) handle(sbn, live = true)
    }

    private fun handle(sbn: StatusBarNotification, live: Boolean) {
        try {
            if (sbn.packageName == applicationContext.packageName) return
            val notif = sbn.notification ?: return
            // Group summaries just duplicate their children
            if (notif.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

            val extras = notif.extras
            val pkg = sbn.packageName ?: "android"
            val pm = applicationContext.packageManager
            val appName = try {
                pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
            } catch (e: Exception) {
                pkg
            }

            try {
                HistoryStore.saveIconIfMissing(applicationContext, pkg, pm.getApplicationIcon(pkg))
            } catch (e: Exception) {
                // ignore
            }

            val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: appName
            val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            val bigText = extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: text
            val subText = extras?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""

            val fingerprint = "${sbn.postTime}|$title|$text|$bigText"
            synchronized(lastSeen) {
                if (lastSeen[sbn.key] == fingerprint) return
                lastSeen[sbn.key] = fingerprint
            }

            val channelId = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notif.channelId ?: "default_channel"
            } else {
                "default_channel"
            }
            var channelName = channelId
            var priority = "Medium"
            try {
                val ranking = Ranking()
                if (currentRanking.getRanking(sbn.key, ranking)) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        ranking.channel?.name?.toString()?.let { channelName = it }
                    }
                    priority = when (ranking.importance) {
                        NotificationManager.IMPORTANCE_MAX -> "Urgent"
                        NotificationManager.IMPORTANCE_HIGH -> "High"
                        NotificationManager.IMPORTANCE_DEFAULT -> "Medium"
                        else -> "Low"
                    }
                }
            } catch (e: Exception) {
                // keep defaults
            }

            val actionsJson = JSONArray()
            notif.actions?.forEach { act ->
                act.title?.let { actionsJson.put(it.toString()) }
            }

            val obj = JSONObject().apply {
                put("id", "sbn_${pkg}_${sbn.id}_${sbn.postTime}")
                put("key", sbn.key)
                put("appName", appName)
                put("packageName", pkg)
                put("channelId", channelId)
                put("channelName", channelName)
                put("category", notif.category ?: "status_bar")
                put("groupKey", sbn.groupKey ?: pkg)
                put("priority", priority)
                put("timestamp", sbn.postTime)
                put("latencyMs", maxOf(0L, System.currentTimeMillis() - sbn.postTime).coerceAtMost(9999L))
                put("device", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
                put("title", title)
                put("text", text)
                put("bigText", if (subText.isNotEmpty()) "$bigText\nSubtext: $subText" else bigText)
                put("actions", actionsJson)
            }

            HistoryStore.append(applicationContext, obj)
            if (live) onNewNotificationCallback?.invoke(obj.toString())
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
