# Logging events

Davy has two kinds of event, each with a name you choose and optional details as strings in `info`:

- A **breadcrumb** records that something happened, such as `checkout_started`.
- An **error** records that something went wrong, such as `payment_declined`.

```swift
// Swift
telemetry.log(.breadcrumb("checkout_started"))
telemetry.log(.breadcrumb("payment_selected", info: ["payment_method": "apple_pay"]))
telemetry.log(.error("payment_declined", info: ["reason": "card_expired"]))
```

```kotlin
// Kotlin
telemetry.log(Event.Breadcrumb("checkout_started"))
telemetry.log(Event.Breadcrumb("payment_selected", mapOf("payment_method" to "google_pay")))
telemetry.log(Event.Error("payment_declined", mapOf("reason" to "card_expired")))
```

Logging returns immediately and is safe from any thread. Each event is written to disk, then sent in the background; see [How delivery works](delivery.md).

Errors and breadcrumbs complement each other. Errors catch the failures you anticipated and handled. Breadcrumbs catch the ones you didn't: if signing in quietly stops working, there may be no error to count, but there will be a drop in `sign_in_completed`.

## Choosing names

Choose short, stable names such as `checkout_started` or `payment_declined`, so events can be counted and grouped across sessions, and use the same names on every platform so one dashboard can compare them.

**Don't include personal data or values that vary per user**, in names or in `info`. That rules out IDs, email addresses, free text, and error descriptions. See [Privacy](privacy.md).

## Diagnostics

Errors have the state of the app and device added to `info` when they're logged: the app's CPU and memory use, the memory it has left, the device's thermal state, and whether power saving is on. They replace any values you set under the same keys, such as `cpu_percent`. See [Diagnostics](backend.md#diagnostics) for the keys and what they mean on each platform.

## Logging platform errors

Pass a Swift `Error` or a Kotlin `Throwable` to log an error with its type under `error_type`, but not its description, which can contain personal data:

```swift
// Swift
do {
    try await checkout()
} catch {
    telemetry.log(error, name: "checkout_failed")
    // Logs .error("checkout_failed", info: ["error_type": "URLError"])
}
```

```kotlin
// Kotlin
try {
    checkout()
} catch (exception: IOException) {
    telemetry.log(exception, "checkout_failed")
    // Logs Event.Error("checkout_failed", mapOf("error_type" to "IOException"))
}
```

Pass `info` to add details. Any `error_type` in it is replaced.

## Timing operations

`measure` runs a block, returns its result, and logs a breadcrumb with how long it took in milliseconds under `duration_ms`. The block can be asynchronous: `async` in Swift, or suspending when called from a Kotlin coroutine.

```swift
// Swift
let feed = try await telemetry.measure("feed_load") {
    try await loadFeed()
}
// Logs .breadcrumb("feed_load", info: ["duration_ms": "412"])
```

```kotlin
// Kotlin
val feed = telemetry.measure("feed_load") {
    loadFeed()
}
// Logs Event.Breadcrumb("feed_load", mapOf("duration_ms" to "412"))
```

The breadcrumb is logged even if the block throws, so log an error as well if you need to tell failures apart.

## Updating session info

Set `info` on `DavyConfiguration` for session details known at launch, and call `addSessionInfo` when they become known or change later, for example after reading remote configuration. It merges the entries into the session's `info`, replacing any existing values for the same keys, and logs a `session_updated` breadcrumb with the entries you passed:

```swift
// Swift
telemetry.addSessionInfo(["edition": "au", "tier": "supporter"])
// Logs .breadcrumb("session_updated", info: ["edition": "au", "tier": "supporter"])
```

```kotlin
// Kotlin
telemetry.addSessionInfo(mapOf("edition" to "au", "tier" to "supporter"))
// Logs Event.Breadcrumb("session_updated", mapOf("edition" to "au", "tier" to "supporter"))
```

Events logged before the call keep the earlier `session.info`, and the `session_updated` breadcrumb and every event logged after it carry the updated `session.info` (starting a new JSON batch when `session.info` changes).

## Sending events now

Events are sent automatically; see [When events are sent](delivery.md#when-events-are-sent). To send everything pending now:

```swift
// Swift
telemetry.flush() // Returns immediately.
await telemetry.flushAndWait() // Waits for the attempt to finish.
```

```kotlin
// Kotlin
telemetry.flush() // Returns immediately.
telemetry.flushAndWait() // Suspends until the attempt finishes.
```

Both skip the debounce interval and any retry backoff, but never a pause your server asked for with `Retry-After`. `flushAndWait()` returns once the server has responded to every pending event, or sending stops because of an error or a pause. Events that weren't delivered stay on disk and are sent later.

## Consistent event names

Set `onInvalidEventName` to enforce lower snake case names: lowercase ASCII letters and digits in words separated by single underscores, starting with a letter, such as `checkout_started` or `step_2`. Names like `checkoutStarted`, `checkout-started`, or `Checkout_Started` are invalid.

| Swift | Kotlin | Invalid names |
| --- | --- | --- |
| `.crash` | `CRASH` | Stop the app at the `log` call: `fatalError` in Swift, an `IllegalArgumentException` in Kotlin |
| `.logError` | `LOG_ERROR` | Log an error to the console or Logcat, and discard the event |
| `.sendError` | `SEND_ERROR` | Send the error `davy_invalid_event_name` instead, with `info` `{ "rejected_name": "checkoutStarted" }` |
| `.ignore` | `IGNORE` | Silently discard the event |
| `.send` | `SEND` | Send the event as is, without checking (default) |

`.sendError` lets you find invalid names in production without mixing them into your other events. The original event's `info` isn't sent, but the rejected name is, so the same no-personal-data rule applies.

For example, crash in debug builds so mistakes are fixed before shipping, but never crash in production:

```swift
// Swift
let telemetry = Davy(configuration: .init(
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

```kotlin
// Kotlin
val telemetry = Davy(context, DavyConfiguration(
    endpoint = URI("https://api.example.com/events"),
    onInvalidEventName = if (BuildConfig.DEBUG) InvalidEventNameAction.CRASH else InvalidEventNameAction.SEND,
))
```

## Event size

Each event must fit in `maxEventSize` bytes (default 2,048), measured as the length of its [query string](backend.md#query-string) URL, including the endpoint, session data, and an error's diagnostics. 2,048 bytes is within the default URL limits of common servers, proxies, and CDNs. The limit applies to every request format, so any event can be sent as a URL, and one large event can't make a whole JSON batch too large for the server to accept.

An event over the limit is replaced with the error `davy_event_too_large`, with `info` `{ "rejected_name": "checkout_started", "size": "2391" }`, and an error is logged to the console. The original event's `info` isn't sent, but an error's diagnostics are. If the replacement is also too large, for example because the name is very long, nothing is sent.

The session data, IDs, timestamps, and a short endpoint take about 340 bytes, leaving roughly 1,700 for the name and `info`. An error's diagnostics take about 190 more. `info` is sent as percent-encoded JSON, so each entry takes about 20 bytes plus its key and value, and each non-ASCII character takes 6 to 12 bytes. Raise `maxEventSize` only if everything between the app and your endpoint accepts longer URLs.

## Foreground and background

Set `logsAppLifecycleEvents` to log breadcrumbs when the app moves between the foreground and background:

| Name | Logged when |
| --- | --- |
| `app_backgrounded` | The app moves to the background, just before pending events are sent, so it's delivered right away |
| `app_foregrounded` | The app returns to the foreground |

Launching isn't logged as `app_foregrounded`, so every `app_foregrounded` follows an `app_backgrounded` in the same session. Davy doesn't log launches, so log your own event at launch, such as `app_launched`, to count sessions. A count of sessions turns raw counts into rates, such as errors per 1,000 sessions, which you can compare across days and builds.

What counts as the background differs by platform: see [iOS and macOS](setup-apple.md#how-it-behaves-on-apple-platforms) and [Android](setup-android.md#how-it-behaves-on-android).
