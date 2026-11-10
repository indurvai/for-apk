package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read app_name and verify HistoryStore 365-day operations`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("NotifyTrace", appName)

        HistoryStore.clearAll(context)
        assertEquals(0, HistoryStore.count(context))

        val testNotif = JSONObject().apply {
            put("id", "sbn_test_1")
            put("appName", "NotifyTrace Test")
            put("packageName", "com.aistudio.notifytrace.ntrxqp")
            put("title", "Test Alert")
            put("text", "Body content")
            put("timestamp", System.currentTimeMillis())
        }
        HistoryStore.append(context, testNotif)
        assertEquals(1, HistoryStore.count(context))

        HistoryStore.deleteById(context, "sbn_test_1")
        assertEquals(0, HistoryStore.count(context))

        HistoryStore.seedDefaultRecords(context)
        assertTrue(HistoryStore.count(context) >= 5)
    }
}
