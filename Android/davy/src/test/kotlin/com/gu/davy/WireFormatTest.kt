package com.gu.davy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class WireFormatTest {
    @Test
    fun eventNamesMustBeLowerSnakeCase() {
        for (name in listOf("checkout_started", "a", "step_2", "http_404_received")) {
            assertTrue(name, Event.isLowerSnakeCase(name))
        }
        val invalidNames = listOf(
            "", "checkoutStarted", "Checkout_started", "CHECKOUT_STARTED", "checkout-started", "checkout started",
            "checkout__started", "_checkout", "checkout_", "2fa_enabled", "café_opened",
        )
        for (name in invalidNames) {
            assertFalse(name, Event.isLowerSnakeCase(name))
        }
    }

    @Test
    fun osVersionsHaveThreeParts() {
        assertEquals("15.0.0", Session.osVersion("15"))
        assertEquals("8.1.0", Session.osVersion("8.1"))
        assertEquals("8.1.0", Session.osVersion("8.1.0"))
        assertEquals("14.0.1", Session.osVersion("14.0.1.2"))
        assertEquals("0.0.0", Session.osVersion("UpsideDownCake"))
        assertEquals("0.0.0", Session.osVersion(null))
    }

    @Test
    fun localesAreLanguageAndRegionOnly() {
        fun locale(language: String, region: String = "") = Locale.Builder().setLanguage(language).setRegion(region).build()

        assertEquals("en_GB", Session.locale(locale("en", "GB")))
        assertEquals("es_419", Session.locale(locale("es", "419")))
        assertEquals("fr", Session.locale(locale("fr")))
        assertEquals("he_IL", Session.locale(locale("iw", "IL")))
        assertEquals("en_US", Session.locale(Locale.forLanguageTag("en-US-u-ca-buddhist")))
        assertNull(Session.locale(Locale.ROOT))
    }

    @Test
    fun retryAfterIsReadAsSecondsOrAnHTTPDate() {
        val now = HttpDate.parse("Wed, 21 Oct 2015 07:27:00 GMT")!!
        fun retryAfter(value: String?, date: String? = null) = DeliveryOutcome.retryAfterSeconds(value, date, now)

        assertEquals(120.0, retryAfter("120"))
        assertEquals(60.0, retryAfter("Wed, 21 Oct 2015 07:28:00 GMT"))
        assertEquals(60.0, retryAfter("Wednesday, 21-Oct-15 07:28:00 GMT"))
        assertEquals(60.0, retryAfter("Wed Oct 21 07:28:00 2015"))
        assertEquals(11.0 * 24 * 60 * 60, retryAfter("Sun Nov  1 07:27:00 2015"))
        // An HTTP date is measured from the server's clock when the response includes one.
        assertEquals(30.0, retryAfter("Wed, 21 Oct 2015 07:28:00 GMT", date = "Wed, 21 Oct 2015 07:27:30 GMT"))
        assertNull(retryAfter("soon"))
        assertNull(retryAfter("-5"))
        assertNull(retryAfter(null))
    }
}
