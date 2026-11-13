package com.gu.davy.example

import android.app.Application
import com.gu.davy.Davy
import com.gu.davy.DavyConfiguration
import com.gu.davy.Event
import com.gu.davy.InvalidEventNameAction
import com.gu.davy.RequestFormat
import java.net.URI
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

class ExampleApplication : Application() {
    /** Keep a single, long-lived instance per storage directory for the lifetime of the app. */
    lateinit var telemetry: Davy
        private set

    override fun onCreate() {
        super.onCreate()
        telemetry = Davy(this, configuration())
        telemetry.log(Event.Breadcrumb("app_launched"))
    }

    /**
     * Where and how events are delivered. Only `endpoint` is required; every other parameter is
     * spelled out here to show what can be configured.
     */
    private fun configuration() = DavyConfiguration(
        // Replace with your own endpoint.
        endpoint = URI("https://api.example.com/events"),
        // POST batches here as `{ "session": { … }, "events": [ … ] }` (the default). Use
        // `RequestFormat.QueryString()` instead to send each event as its own request to
        // `<endpoint>/<name>?eventId=…&type=…&sessionId=…`, for servers that log URLs, not bodies.
        format = RequestFormat.JsonBatch,
        // Lets you separate test data from real usage, for example "beta" for internal test tracks.
        environment = if (BuildConfig.DEBUG) "debug" else "production",
        // Throw on names that aren't lower snake case (like "checkoutStarted") in debug builds so
        // they're fixed before shipping, but ignore them in release builds. `SEND_ERROR` reports them
        // as a "davy_invalid_event_name" error instead, and `LOG_ERROR` discards them.
        onInvalidEventName = if (BuildConfig.DEBUG) InvalidEventNameAction.CRASH else InvalidEventNameAction.IGNORE,
        // Replace events larger than this many bytes, measured as a query string URL, with a
        // "davy_event_too_large" error (default 2,048, within common server URL limits).
        maxEventSize = 2048,
        // Log "app_backgrounded" when the app moves to the background, just before pending events
        // are sent, and "app_foregrounded" when it returns (default false). Launching isn't logged,
        // so this app logs its own "app_launched".
        logsAppLifecycleEvents = true,
        headers = mapOf("Authorization" to "Bearer <your-api-token>"),
        // Send as soon as 20 events are pending (default 100).
        batchSize = 20,
        // Or after 5 seconds without a new event (default 10 seconds).
        debounceInterval = 5.seconds,
        // Discard events that still haven't been delivered after 7 days (default 30 days).
        maxEventAge = 7.days,
        storageDirectory = Davy.defaultStorageDirectory(this),
    )
}
