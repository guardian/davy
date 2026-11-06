<p align="center">
  <img src="Davy-Logo.png" alt="Davy: Dedicated App Vitals for You. A safety lamp for your app." width="240">
</p>

# Davy

**A safety lamp for your app.** Davy is a lightweight, privacy-minded telemetry client for iOS 18+ and macOS 15+. You log small, anonymous events, and Davy stores them on the device and delivers them to an endpoint you run. Bring your own backend.

Like the miner's lamp it's named after, Davy is an early warning system. Use it to answer questions about your app as a whole:

- Is an error happening more often than usual?
- Has a key action, such as signing in, suddenly dropped or surged?
- Is the new build behaving like the previous one?

Davy is built for big-picture reporting: how often something happens, which errors are most common, and what led up to them. It isn't for product analytics, crash reporting, or looking into individual users or sessions.

## Features

- **Two simple kinds of event.** Breadcrumbs record that something happened, and errors record that something went wrong. Each has a name you choose and optional string details.
- **Context for errors.** Each error records the app's CPU and memory usage, the device's thermal state, and Low Power Mode, at the moment it was logged.
- **Cheap to call.** `log(_:)` is synchronous, returns immediately, and is safe to call from any thread.
- **Reliable delivery.** Every event is written to disk before it's sent, so events survive crashes, relaunches, and going offline. Failed requests are retried with exponential backoff.
- **Your server stays in control.** It can pause every client with `Retry-After`, and remove duplicates by event ID.
- **Privacy-minded.** No user, device, or advertising identifiers, coarse session data, and a new random session ID every launch.
- **Bring your own backend.** Events are sent as JSON batches, or one request per event with every field in the URL for servers that can only log URLs.
- **Works in app extensions**, such as a Notification Service Extension.
- **A built-in debug view** that lists pending and sent events during development.
- **No dependencies.**

## Contents

- [Requirements](#requirements)
- [Installation](#installation)
- [Quick start](#quick-start)
- [Logging events](#logging-events)
- [SwiftUI](#swiftui)
- [App extensions](#app-extensions)
- [Debugging events](#debugging-events)
- [Configuration](#configuration)
- [How delivery works](#how-delivery-works)
- [Your backend](#your-backend)
- [Privacy](#privacy)
- [Example app](#example-app)
- [Running the tests](#running-the-tests)

## Requirements

- iOS 18 or later, or macOS 15 or later, including app extensions
- Xcode 27 or later (Swift 6.4)
- An endpoint of your own to receive events; see [Your backend](#your-backend)

## Installation

Add Davy with Swift Package Manager.

In Xcode, choose **File > Add Package Dependencies…**, enter `https://github.com/guardian/davy`, and add the **Davy** library to your app target and to any app extension targets that log events.

Or, in `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/guardian/davy.git", branch: "main"),
],
targets: [
    .target(
        name: "MyApp",
        dependencies: [
            .product(name: "Davy", package: "davy"),
        ]
    ),
]
```

## Quick start

Create one instance when your app launches, and keep it for the app's lifetime:

```swift
import Davy

let analytics = Davy(configuration: .init(
    endpoint: URL(string: "https://api.example.com/events")!,
    environment: "production",
    headers: ["Authorization": "Bearer <token>"]
))

// Davy doesn't log launches, so log your own to count sessions.
analytics.log(.breadcrumb("app_launched"))
```

- **Use one instance per storage directory.** Each instance starts a new session and owns the events in its [`storageDirectory`](#configuration), so creating several that share a directory can send events twice. Keep it in your app's dependency container, or see [SwiftUI](#swiftui).
- **Log a launch breadcrumb.** A count of sessions turns raw counts into rates, such as errors per 1,000 sessions, which you can compare across days and builds.
- **Set `environment`** to keep test data apart from real usage, for example `"debug"`, `"beta"`, or `"production"`. TestFlight and App Store builds share a binary, so telling them apart needs a runtime check or a separate build configuration.

`Davy` is `Sendable`, so you can share the instance across threads, tasks, and actors.

## Logging events

```swift
// A breadcrumb records that something happened.
analytics.log(.breadcrumb("checkout_started"))

// Optionally add details as a dictionary of strings.
analytics.log(.breadcrumb("payment_selected", info: ["payment_method": "apple_pay"]))

// An error records that something went wrong.
analytics.log(.error("payment_declined", info: ["reason": "card_expired"]))
```

Errors and breadcrumbs complement each other. Errors catch the failures you anticipated and handled. Breadcrumbs catch the ones you didn't: if signing in quietly stops working, there may be no error to count, but there will be a drop in `sign_in_completed`.

Choose short, stable names such as `"checkout_started"` or `"payment_declined"` so events can be counted and grouped across sessions. **Don't include personal data or values that vary per user**, in names or in `info`. That rules out IDs, email addresses, free text, and error descriptions.

Errors also have [diagnostics](#diagnostics) added to `info` when they're logged: the app's CPU and memory usage, the memory it has left, the device's thermal state, and whether Low Power Mode is on. They replace any values you set under the same keys, such as `cpu_percent`.

### Logging Swift errors

Pass a Swift `Error` to log it with its type, but not its description, which can contain personal data:

```swift
do {
    try await checkout()
} catch {
    analytics.log(error, name: "checkout_failed")
    // Logs .error("checkout_failed", info: ["error_type": "URLError"])
}
```

Pass `info` to add details. Any `error_type` key in it is replaced.

### Timing operations

`measure(_:_:)` runs a closure, synchronous or `async`, returns its result, and logs a breadcrumb with how long it took in milliseconds under `duration_ms`:

```swift
let feed = try await analytics.measure("feed_load") {
    try await loadFeed()
}
// Logs .breadcrumb("feed_load", info: ["duration_ms": "412"])
```

The breadcrumb is logged even if the closure throws, so log an error as well if you need to tell failures apart.

### Sending events now

Events are sent automatically; see [When events are sent](#when-events-are-sent). To send everything pending now:

```swift
// Returns immediately.
analytics.flush()

// Or wait for the attempt to finish.
await analytics.flushAndWait()
```

Both skip the debounce interval and any retry backoff, but never a pause your server asked for with `Retry-After`. `flushAndWait()` returns once the server has responded to every pending event, or sending stops because of an error or a pause. Events that weren't delivered stay on disk and are sent later.

### Consistent event names

Set `onInvalidEventName` to enforce lower snake case names: lowercase ASCII letters and digits in words separated by single underscores, starting with a letter (`"checkout_started"`, `"step_2"`). Names like `"checkoutStarted"`, `"checkout-started"`, or `"Checkout_Started"` are invalid.

| Action | Invalid names |
| --- | --- |
| `.crash` | Stop the app with `fatalError` at the `log(_:)` call |
| `.logError` | Log an error to the console and discard the event |
| `.sendError` | Send the error `"davy_invalid_event_name"` instead, with `info` `{ "rejected_name": "checkoutStarted" }` |
| `.ignore` | Silently discard the event |
| `.send` | Send the event as is, without checking (default) |

`.sendError` lets you find invalid names in production without mixing them into your other events. The original event's `info` isn't sent, but the rejected name is, so the same no-personal-data rule applies.

For example, crash in debug builds so mistakes are fixed before shipping, but never crash in production:

```swift
let analytics = Davy(configuration: .init(
    endpoint: URL(string: "https://api.example.com/events")!,
    onInvalidEventName: {
        #if DEBUG
        .crash
        #else
        .send
        #endif
    }()
))
```

### Event size

Each event must fit in `maxEventSize` bytes (default 2,048), measured as the length of its [query string](#query-string) URL, including the endpoint, session data, and an error's diagnostics. 2,048 bytes is within the default URL limits of common servers, proxies, and CDNs. The limit applies to both formats, so any event can be sent as a URL, and one large event can't make a whole JSON batch too large for the server to accept.

An event over the limit is replaced with the error `"davy_event_too_large"`, with `info` `{ "rejected_name": "checkout_started", "size": "2391" }`, and an error is logged to the console. The original event's `info` isn't sent, but an error's diagnostics are. If the replacement is also too large, for example because the name is very long, nothing is sent.

The session data, IDs, timestamp, and a short endpoint take about 300 bytes, leaving roughly 1,700 for the name and `info`. An error's diagnostics take about 190 more. `info` is sent as percent-encoded JSON, so each entry takes about 20 bytes plus its key and value, and each non-ASCII character takes 6 to 12 bytes. Raise `maxEventSize` only if everything between the app and your endpoint accepts longer URLs.

### Foreground and background

Set `logsAppLifecycleEvents` to log breadcrumbs when the app moves between the foreground and background:

| Name | Logged when |
| --- | --- |
| `app_backgrounded` | The app moves to the background (iOS) or resigns active (macOS), just before pending events are sent, so it's delivered right away |
| `app_foregrounded` | The app returns to the foreground (iOS) or becomes active again (macOS) |

Launching isn't logged as `app_foregrounded`, so every `app_foregrounded` follows an `app_backgrounded` in the same session. Log your own event at launch if you need one. Like the automatic flush, this doesn't apply to app extensions.

## SwiftUI

Create the instance in your `App`, and pass it to your views through the environment with `\.analytics`:

```swift
import Davy
import SwiftUI

@main
struct MyApp: App {
    private let analytics = Davy(configuration: .init(
        endpoint: URL(string: "https://api.example.com/events")!
    ))

    init() {
        analytics.log(.breadcrumb("app_launched"))
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(\.analytics, analytics)
        }
    }
}
```

Read it in any view. It's `nil` by default, so previews and views outside your app's hierarchy never log or send events:

```swift
struct CheckoutView: View {
    @Environment(\.analytics) private var analytics

    var body: some View {
        Button("Pay") {
            analytics?.log(.breadcrumb("pay_tapped"))
        }
        .logBreadcrumb("checkout_viewed")
    }
}
```

`logBreadcrumb(_:)` logs a breadcrumb each time the view appears, so it can be logged more than once, for example when navigating back to the view.

## App extensions

Davy can also be used in app extensions, for example to record when a push notification arrives in a Notification Service Extension. Extensions aren't flushed automatically and can be suspended as soon as they finish, so call `flushAndWait()` first:

```swift
import Davy
import UserNotifications

final class NotificationService: UNNotificationServiceExtension {
    // One instance per process, which the system may reuse for several notifications.
    static let analytics = Davy(configuration: .init(
        endpoint: URL(string: "https://api.example.com/events")!
    ))

    override func didReceive(
        _ request: UNNotificationRequest,
        withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        let pushID = request.content.userInfo["push_id"] as? String ?? "unknown"
        Self.analytics.log(.breadcrumb("push_received", info: ["push_id": pushID]))
        Task {
            await Self.analytics.flushAndWait()
            contentHandler(request.content)
        }
    }
}
```

- An extension has its own container, so the default `storageDirectory` is already separate from the app's.
- Events that aren't delivered before the extension is suspended stay on disk and are sent the next time it runs.
- `flushAndWait()` can take as long as the network request, so keep handling `serviceExtensionTimeWillExpire()`.
- A Notification Service Extension only runs for notifications sent with `mutable-content: 1`.

## Debugging events

`DavyDebugView` lists the events waiting to be sent and the events sent since launch, so you can check what your app logs during development. It opens on an overview showing the current session ID, how many events are pending, and how many were logged and sent in the last hour, with a line chart of both per minute over a rolling one-hour window and dotted lines where each session's first event was logged. The events list shows pending and sent events together, most recently logged first, with an icon showing whether each has been sent. Select an event to see its ID, timestamps, status, `info`, session data, and JSON. If sending fails, the reason is shown at the top of the overview and the events list. The toolbar sends pending events now and clears the sent history.

Present it from a debug menu. It shows the instance set in the environment with `\.analytics`:

```swift
@State private var showsDebugEvents = false

Button("Debug Events") {
    showsDebugEvents = true
}
.davyDebugSheet(isPresented: $showsDebugEvents)
```

Or present `DavyDebugView(analytics: analytics)` yourself. It has its own `NavigationStack`, so present it rather than pushing it. It's a development tool, so its text is in English and isn't localized.

To build your own tools, read the same data with `debugSnapshot()`, or get a new snapshot each time it changes with `debugUpdates()`:

```swift
let snapshot = await analytics.debugSnapshot()
print(snapshot.pending.map(\.name)) // Oldest first, the order they're sent in
print(snapshot.sent.map(\.sentAt)) // Oldest first
print(snapshot.lastFailure?.reason) // nil once a request succeeds
print(snapshot.activity.map(\.logged)) // Events logged each minute over the last hour

for await snapshot in analytics.debugUpdates() {
    // Called right away, then each time events are logged, sent, or fail to send.
}
```

- Pending events are read from disk, so they include events left over from previous launches, and events in requests that haven't finished.
- Sent events include events the server rejected, with `outcome` `.rejected(statusCode:)`. They're kept in memory only, up to `debugHistoryLimit` (default 200), oldest dropped first. Set it to `0` to keep none, and call `clearDebugHistory()` to empty it.
- `activity` counts the events logged and sent in each minute of the last hour, leaving out quiet minutes. It includes events left over from earlier launches, counted as logged when they were logged. It's kept in memory regardless of `debugHistoryLimit`, and `clearDebugHistory()` doesn't empty it.
- `sessionStarts` lists when each session's first event was logged, for sessions that started in the last hour, including sessions from earlier launches whose events were still pending.
- `analytics.sessionID` is the ID sent with this launch's events, so you can find them on your server.

## Configuration

Only `endpoint` is required.

| Property | Default | Description |
| --- | --- | --- |
| `endpoint` | — | URL that events are sent to |
| `format` | `.jsonBatch` | `.jsonBatch` or `.queryString(method:maxConcurrentRequests:)`; see [Request formats](#request-formats) |
| `environment` | `nil` | The kind of build, for example `"debug"`, `"beta"`, or `"production"`; sent as `session.environment` |
| `onInvalidEventName` | `.send` | What to do with event names that aren't lower snake case: `.crash`, `.logError`, `.sendError`, `.ignore`, or `.send`; see [Consistent event names](#consistent-event-names) |
| `maxEventSize` | `2048` | Largest event in bytes, measured as its query string URL; larger events are replaced with an error; see [Event size](#event-size) |
| `logsAppLifecycleEvents` | `false` | Log `app_foregrounded` and `app_backgrounded` breadcrumbs; see [Foreground and background](#foreground-and-background) |
| `headers` | `[:]` | Extra HTTP headers for every request, for example `Authorization` |
| `batchSize` | `100` | Pending count that triggers a send; with `.jsonBatch`, also max events per request |
| `debounceInterval` | `10s` | Quiet period before sending a partial batch |
| `maxEventAge` | `30 days` | Age after which unsent events are discarded |
| `storageDirectory` | `Application Support/<bundle id>/Davy` | Where pending events are stored; use a separate directory for each instance |
| `debugHistoryLimit` | `200` | Sent events kept in memory for `DavyDebugView` and `debugSnapshot()`; `0` keeps none; see [Debugging events](#debugging-events) |

`Example/Davy/MyApp.swift` sets every property, with a comment explaining each one.

## How delivery works

### When events are sent

- When `batchSize` events are pending (default 100). With `.jsonBatch`, each request contains at most `batchSize` events.
- When `debounceInterval` passes without a new event (default 10 seconds).
- When the app moves to the background (iOS) or resigns active (macOS).
- On launch, for any events left over from a previous session.
- When `flush()` or `flushAndWait()` is called.

The debounce timer restarts with every event, so in a busy session events can wait until `batchSize` are pending or the app moves to the background. Lower `debounceInterval` and `batchSize` if you need events sooner.

### Delivery guarantees

- Every event is written to disk before it is sent, so events survive crashes, relaunches, and offline use.
- Events are sent oldest first. `.jsonBatch` sends one request at a time. `.queryString` sends up to `maxConcurrentRequests` at once, so events can arrive out of order; use `timestamp` to order them.
- Events are removed only once the server accepts or permanently rejects them. Network errors and other responses keep them for a retry.
- Delivery is at least once. If a response is lost, for example because the app quits or the connection drops after the server stored the events, they're sent again with the same IDs.
- Breadcrumbs and errors share one queue, so they're stored, retried, and expired together and keep their relative order.

Events are discarded without being delivered when:

- Their name is invalid and `onInvalidEventName` is `.logError` or `.ignore`.
- They're over `maxEventSize`, and so is the error that would replace them.
- They're older than `maxEventAge` (default 30 days).
- The server permanently rejects them; see below.
- They can't be written to or read from disk, for example because the disk is full. An error is logged to the console.

### Responses and retries

| Response | What happens |
| --- | --- |
| 2xx | Events are removed |
| 429 or 503 with `Retry-After` | All sending pauses for that long, even when flushing or relaunching, then resumes with a single request |
| 400, 413, 414, 422, or 431 | Events are discarded, since the server will never accept them, so they don't block the queue |
| Anything else, or a network error | Events are kept. No new requests start; retries use exponential backoff with jitter (up to 5 seconds, doubling to at most 5 minutes), starting with a single request |

`Retry-After` can be a number of seconds or an HTTP date, which is measured from the response's `Date` header if present so a wrong device clock doesn't matter. Pauses are at least 1 second and at most 24 hours, lengthened by up to 10% at random so devices don't all return at once. `flush()`, `flushAndWait()`, and backgrounding skip backoff after errors, but never a `Retry-After` pause.

401, 403, and 404 are retried rather than discarded, since they usually mean the endpoint is misconfigured. A 429 or 503 without a valid `Retry-After` is retried with backoff.

## Your backend

Davy is only the client. You run the endpoint, and what's behind it is up to you: a small web service that writes to a database, a serverless function, or a CDN that logs each request straight to a data warehouse. Only the endpoint URL is built into your app, so you can change everything behind it without an app update.

Your endpoint should:

1. **Accept your chosen [request format](#request-formats).**
2. **Respond with 2xx once the events are stored.** The response body is ignored.
3. **Remove duplicates by event ID** (`id`, or `eventId` in the query string format), since [delivery is at least once](#delivery-guarantees).
4. **Respond with 400, 413, 414, 422, or 431** to requests it will never accept, so their events are discarded instead of retried.
5. **Respond with 429 or 503 and `Retry-After`** to slow down or pause clients, for example during an incident or a cost spike.
6. **Expect late events.** Devices send events when they come back online, up to `maxEventAge` (default 30 days) after they were logged, so the most recent minutes of data are always incomplete. Allow for this in dashboards and alerts.
7. **Record when it received each request**, if you need a trusted time. `timestamp` comes from the device's clock, which can be wrong.
8. **Treat the endpoint as public.** Anything built into an app, including an `Authorization` header, can be extracted from it, so validate what you receive.
9. **Not log IP addresses**, if you want to keep reports anonymous.

### Request formats

Set `format` to choose how events are encoded:

| Format | Requests |
| --- | --- |
| `.jsonBatch` | Up to `batchSize` events per `POST`, as a JSON body (default) |
| `.queryString(method:maxConcurrentRequests:)` | One request per event with an empty body and every field in the URL; `POST` (default) or `GET`, up to 4 in flight by default |

Every request includes the headers in `headers`. Requests never use cookies or the local cache.

#### JSON batch

A `POST` with `Content-Type: application/json`. Each request contains events from a single session, along with common app and device data captured when that session started:

```json
{
  "session": {
    "id": "6F9619FF-8B86-D011-B42D-00C04FC964FF",
    "environment": "production",
    "appVersion": "1.2.0",
    "build": "45",
    "os": "iOS",
    "osVersion": "26.1.0",
    "deviceModel": "iPhone17,1",
    "locale": "en_GB"
  },
  "events": [
    { "id": "1B4E28BA-2FA1-11D2-883F-0016D3CCA427", "type": "breadcrumb", "name": "checkout_started", "timestamp": "2026-11-09T11:38:55.780Z" },
    {
      "id": "9C5B94B1-35AD-49BB-B118-8E8FC24ABF80",
      "type": "error",
      "name": "payment_declined",
      "info": {
        "reason": "card_expired",
        "cpu_percent": "12",
        "memory_used_mb": "143",
        "memory_available_mb": "2650",
        "thermal_state": "nominal",
        "low_power_mode": "false"
      },
      "timestamp": "2026-11-09T11:38:57.112Z"
    }
  ]
}
```

#### Query string

For servers that can log fields from the URL but not from the body, such as a CDN logging requests straight to a data warehouse. The event's name is appended to the endpoint's path, and every other field is a query parameter, omitted if it has no value:

```
POST https://api.example.com/events/payment_declined?eventId=9C5B94B1-…&type=error&timestamp=2026-11-09T11%3A38%3A57.112Z&sessionId=6F9619FF-…&environment=production&appVersion=1.2.0&build=45&os=iOS&osVersion=26.1.0&deviceModel=iPhone17%2C1&locale=en_GB&info=%7B%22cpu_percent%22%3A%2212%22%2C%22low_power_mode%22%3A%22false%22%2C%22memory_available_mb%22%3A%222650%22%2C%22memory_used_mb%22%3A%22143%22%2C%22reason%22%3A%22card_expired%22%2C%22thermal_state%22%3A%22nominal%22%7D
```

- Parameters are `eventId`, `type`, `timestamp`, and `info` from the event, and `sessionId`, `environment`, `appVersion`, `build`, `os`, `osVersion`, `deviceModel`, and `locale` from the session.
- `info` is a JSON object string with sorted keys.
- Everything except `A–Z a–z 0–9 - . _ ~` is percent-encoded, including `+`, so values decode the same way on every server.
- Query items already in the endpoint are kept.
- Requests are never answered from the local cache or send cookies. With `GET`, the server should also respond with `Cache-Control: no-store` so proxies don't cache it either.
- URLs are at most `maxEventSize` bytes (default 2,048); see [Event size](#event-size). If you raise it, note that a server rejecting a URL as too long with 414 causes the event to be discarded.

### Session fields

| Field | Description |
| --- | --- |
| `id` | A random UUID generated when the `Davy` instance is created (typically once per launch); `sessionId` in the query string format |
| `environment` | `DavyConfiguration.environment`, for example `debug`, `beta`, or `production`; omitted if `nil` |
| `appVersion` | `CFBundleShortVersionString`; omitted if missing |
| `build` | `CFBundleVersion`; omitted if missing |
| `os` | `iOS`, `macOS`, `macCatalyst`, `visionOS`, `tvOS`, or `watchOS` |
| `osVersion` | `major.minor.patch`, for example `26.1.0` |
| `deviceModel` | Hardware model identifier, for example `iPhone17,1` or `Mac15,3`, or `Simulator` |
| `locale` | Device language and region only, for example `en_GB`, or just the language if there's no region |

The session ID is kept in memory and never reused, so it links events within a session without identifying the user or device across sessions. Events left over from a previous launch are sent in their own request with their original session data, so they're attributed to the app and OS version they were logged in, even after an update.

### Event fields

| Field | Description |
| --- | --- |
| `id` | A random UUID generated when the event is logged and sent unchanged with every retry, so the server can discard duplicates; `eventId` in the query string format |
| `type` | `"breadcrumb"` or `"error"` |
| `name` | The name passed to `.breadcrumb(_:info:)` or `.error(_:info:)`; part of the path in the query string format |
| `info` | The `info` dictionary of strings, with [diagnostics](#diagnostics) added for errors; omitted if empty |
| `timestamp` | When `log(_:)` was called, by the device's clock, as ISO 8601 in UTC with fractional seconds |

### Diagnostics

Added to each error's `info` during the `log(_:)` call, to help explain it, for example by showing that an error only happens when the app is low on memory or the device is overheating. They replace any values already in `info` under the same keys. Capturing takes a few microseconds per thread in the app, so breadcrumbs don't include them.

| Key | Description |
| --- | --- |
| `cpu_percent` | The app's CPU usage as a whole-number percentage of one core, summed across its threads, so it can be over 100 on a device with several cores. The system averages each thread's usage over the last few seconds |
| `memory_used_mb` | The app's memory footprint in megabytes, as shown by Xcode's memory gauge and compared with the app's memory limit |
| `memory_available_mb` | How many more megabytes the app can use before the system terminates it; omitted where there's no limit, such as on macOS or in Simulator |
| `thermal_state` | `nominal`, `fair`, `serious`, or `critical` |
| `low_power_mode` | Whether Low Power Mode is on, `true` or `false` |

Like all `info` values, they're strings. Each key except `low_power_mode` is omitted if its value can't be read.

## Privacy

Session data is deliberately coarse: it describes the kind of device and app build an event came from, not the person using it. Davy doesn't collect device names, advertising or vendor identifiers, persistent IDs, time zones, screen sizes, or custom locale settings. Requests never send cookies, so they can't be linked to each other that way.

Errors' diagnostics describe the app's resource usage and the device's conditions at that moment, rounded to whole numbers. Davy doesn't collect battery level or free disk space, which can help identify a device.

Davy only sends the events you log, their session data, and diagnostics for errors, and only to your endpoint. Reports stay anonymous only if your event names and `info` contain no personal data. Your server still sees each request's IP address, so avoid logging it if you want to keep reports anonymous.

## Example app

`Example/Davy Example.xcodeproj` is a SwiftUI app that owns one instance, passes it through the environment, logs events from the UI, and presents the debug view. Its configuration sets every property, with a comment explaining each one.

Its endpoint is a placeholder, so requests fail and events stay pending, which shows how failures and retries appear in the debug view. Replace it with your own endpoint to see events arrive.

## Running the tests

From the package's root directory:

```sh
swift test
```

Or open `Package.swift` in Xcode and choose **Product > Test**.
