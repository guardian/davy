package com.gu.davy

import android.app.Activity
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.ListView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URI
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DavyDebugActivityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun listsEventsAndShowsTheirDetails() {
        val directory = File(context.cacheDir, "DavyDebugActivityTest-${UUID.randomUUID()}")
        // Nothing listens on port 1, so sending fails and the events stay pending.
        val telemetry = Davy(context, DavyConfiguration(endpoint = URI("http://127.0.0.1:1/events"), storageDirectory = directory))
        val activities = mutableListOf<Activity>()
        try {
            telemetry.log(Event.Breadcrumb("checkout_started"))
            telemetry.log(Event.Error("payment_declined", mapOf("reason" to "card_expired")))
            val flushed = CountDownLatch(1)
            telemetry.flush { flushed.countDown() }
            assertTrue(flushed.await(10, TimeUnit.SECONDS))

            val intent = DavyDebugActivity.intent(context, telemetry).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val list = instrumentation.startActivitySync(intent) as DavyDebugActivity
            activities += list
            assertTrue(waitUntil { onMain { list.listedEvents.size == 2 } })
            val listTexts = onMain { texts(list.window.decorView) }
            assertTrue(listTexts.toString(), DavyJson.uuid(telemetry.sessionId) in listTexts)
            assertTrue(listTexts.toString(), listTexts.any { it.startsWith("LAST SEND FAILED AT") })
            assertTrue(listTexts.toString(), "EVENTS (2)" in listTexts)
            assertEquals(listOf("payment_declined", "checkout_started"), onMain { list.listedEvents.map { it.name } })

            // Searching and filtering narrow the list.
            onMain { list.searchText = "CHECKOUT" }
            assertEquals(listOf("checkout_started"), onMain { list.listedEvents.map { it.name } })
            assertTrue(onMain { "EVENTS (1 OF 2)" in texts(list.window.decorView) })
            onMain {
                list.searchText = ""
                list.eventFilter = DebugEventFilter.ERRORS
            }
            assertEquals(listOf("payment_declined"), onMain { list.listedEvents.map { it.name } })
            onMain { list.eventFilter = DebugEventFilter.ALL }

            // Select the error, which was logged most recently, so it's listed first, after the header.
            val monitor = instrumentation.addMonitor(DavyDebugActivity::class.java.name, null, false)
            onMain { views<ListView>(list.window.decorView).single().let { it.performItemClick(null, it.headerViewsCount, 0) } }
            val detail = monitor.waitForActivityWithTimeout(5_000) as DavyDebugActivity
            instrumentation.removeMonitor(monitor)
            activities += detail
            assertTrue(waitUntil { onMain { "JSON" in texts(detail.window.decorView) } })
            val detailTexts = onMain { texts(detail.window.decorView) }

            assertEquals("payment_declined", onMain { detail.title.toString() })
            assertTrue(detailTexts.toString(), "Error" in detailTexts)
            assertTrue(detailTexts.toString(), "Pending" in detailTexts)
            assertTrue(detailTexts.toString(), "card_expired" in detailTexts)
            assertTrue(detailTexts.toString(), detailTexts.any { "\"reason\" : \"card_expired\"" in it })

            // The event lists the requests that included it; select one to see the request.
            assertTrue(detailTexts.toString(), "REQUESTS" in detailTexts)
            val request = select(detail, "2 events")
            activities += request
            assertTrue(waitUntil { onMain { "BODY" in texts(request.window.decorView) } })
            val requestTexts = onMain { texts(request.window.decorView) }
            assertEquals("Request", onMain { request.title.toString() })
            assertTrue(requestTexts.toString(), "No response" in requestTexts)
            assertTrue(requestTexts.toString(), "Failed" in requestTexts)
            assertTrue(requestTexts.toString(), "REASON" in requestTexts)
            assertTrue(requestTexts.toString(), "EVENTS (2)" in requestTexts)
            assertTrue(requestTexts.toString(), "Content-Type: application/json" in requestTexts)
            assertTrue(requestTexts.toString(), requestTexts.any { "\"name\" : \"payment_declined\"" in it })

            // The list of requests is searched by body.
            assertTrue(onMain { texts(list.window.decorView) }.any { it.startsWith("REQUESTS (") })
            val requests = select(list, "Show Requests")
            activities += requests
            assertTrue(waitUntil { onMain { requests.listedRequests.isNotEmpty() } })
            assertEquals("Requests", onMain { requests.title.toString() })
            onMain { requests.searchText = "PAYMENT_DECLINED" }
            assertTrue(onMain { requests.listedRequests.isNotEmpty() })
            onMain { requests.searchText = "refund" }
            assertTrue(onMain { requests.listedRequests.isEmpty() })
            assertTrue(onMain { "No requests match the search." in texts(requests.window.decorView) })
        } finally {
            onMain { activities.forEach(Activity::finish) }
            telemetry.close()
            directory.deleteRecursively()
        }
    }

    /** Selects the row containing [text] in [activity], returning the screen it opens. */
    private fun select(activity: Activity, text: String): DavyDebugActivity {
        val monitor = instrumentation.addMonitor(DavyDebugActivity::class.java.name, null, false)
        onMain {
            val label = views<TextView>(activity.window.decorView).first { it.text.toString() == text }
            // Each row is a label in a column beside its mark or chevron.
            (label.parent.parent as View).performClick()
        }
        val opened = monitor.waitForActivityWithTimeout(5_000) as DavyDebugActivity
        instrumentation.removeMonitor(monitor)
        return opened
    }

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        instrumentation.runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    private fun texts(view: View): List<String> = views<TextView>(view).map { it.text.toString() }

    private inline fun <reified T : View> views(view: View): List<T> = allViews(view).filterIsInstance<T>()

    private fun allViews(view: View): List<View> =
        listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { allViews(view.getChildAt(it)) } else emptyList()

    private fun waitUntil(timeoutMillis: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(50)
        }
        return condition()
    }
}
