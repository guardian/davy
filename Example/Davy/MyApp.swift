import SwiftUI
import Davy

@main struct MyApp: App {
    /// Keep a single, long-lived instance per storage directory for the lifetime of the app.
    private let telemetry = Davy(configuration: DavyConfig)

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(\.telemetry, telemetry)
        }
    }

    init() {
        telemetry.log(.breadcrumb("app_launched"))
    }
}

/// Where and how events are delivered. Only `endpoint` is required; every other parameter is
/// spelled out here to show what can be configured.
private let DavyConfig = DavyConfiguration(
    // Debug builds send to the test server in `TestServer/`, running on this Mac; see
    // docs/development.md. On a device, replace `localhost` with the Mac's IP address.
    endpoint: {
        #if DEBUG
        URL(string: "http://localhost:8080/events")!
        #else
        // Replace with your own endpoint.
        URL(string: "https://api.example.com/events")!
        #endif
    }(),
    // POST batches here as `{ "session": { … }, "events": [ … ] }` (the default). Use
    // `.jsonEvent(maxConcurrentRequests:)` to send each event as its own request here, with the
    // same body containing just that event. Use `.queryString(method:maxConcurrentRequests:)` to
    // send each event as its own request to `<endpoint>/<name>?eventId=…&type=…&sessionId=…`, for
    // servers that log URLs, not bodies.
    format: .jsonBatch,
    // Lets you separate test data from real usage. TestFlight and App Store builds share a binary,
    // so a "beta" value needs a runtime check or a separate build configuration.
    environment: {
        #if DEBUG
        "debug"
        #else
        "production"
        #endif
    }(),
    // Crash on names that aren't lower snake case (like "checkoutStarted") in debug builds so they're
    // fixed before shipping, but still send them in release builds. `.sendError` reports them as a
    // "davy_invalid_event_name" error instead, and `.logError` and `.ignore` discard them.
    onInvalidEventName: {
        #if DEBUG
        .crash
        #else
        .ignore
        #endif
    }(),
    // Replace events larger than this many bytes, measured as a query string URL, with a
    // "davy_event_too_large" error (default 2,048, within common server URL limits).
    maxEventSize: 2048,
    // Log "app_backgrounded" when the app moves to the background, just before pending events are
    // sent, and "app_foregrounded" when it returns (default false). Launching isn't logged, so
    // this app logs its own "app_launched".
    logsAppLifecycleEvents: true,
    headers: ["Authorization": "Bearer <your-api-token>"],
    // Send as soon as 20 events are pending (default 100).
    batchSize: 20,
    // Or after 5 seconds without a new event (default 10 seconds).
    debounceInterval: .seconds(5),
    // Discard events that still haven't been delivered after 7 days (default 30 days).
    maxEventAge: .seconds(7 * 24 * 60 * 60),
    storageDirectory: DavyConfiguration.defaultStorageDirectory,
    // Keep the 200 most recently sent events in memory for the "Debug Events" screen (the
    // default). 0 keeps none.
    debugHistoryLimit: 200
)
