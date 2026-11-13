# Debugging events

Each platform has a debug screen that lists the events waiting to be sent and the events sent since launch, so you can check what your app logs during development. It's a development tool, so its text is in English and isn't localized.

| Platform | Debug screen | How to show it |
| --- | --- | --- |
| iOS and macOS | `DavyDebugView` | `.davyDebugSheet(isPresented:)`, or present `DavyDebugView(telemetry:)`; see [setup](setup-apple.md#showing-the-debug-view) |
| Android | `DavyDebugActivity` | `DavyDebugActivity.start(context, telemetry)`; see [setup](setup-android.md#showing-the-debug-screen) |

## What it shows

- **A summary:** the current session ID, how many events are pending, and how many were logged and sent in the last hour. On Apple platforms, a chart shows both per minute over the last hour, with dotted lines where each session's first event was logged. Android has no built-in chart, so it shows the totals only.
- **The last failure:** if sending fails, the reason is shown at the top. Select it to see all of it.
- **The events:** pending and sent events together, most recently logged first, with a mark showing whether each is pending, sent, or rejected.
- **Search and filtering:** search by name, `info` key or value, or event ID, ignoring case, and choose **Show** to list only pending, sent, rejected, or error events.
- **Event details:** select an event to see its ID, when it was logged, its status, its `info`, its session data, and its JSON, which you can share.
- **Actions:** send pending events now, and clear the sent history.

## Snapshots

To build your own tools, read the same data with `debugSnapshot()`, or get a new snapshot each time it changes:

```swift
// Swift
let snapshot = await telemetry.debugSnapshot()
print(snapshot.pending.map(\.name)) // Oldest first, the order they're sent in
print(snapshot.sent.map(\.sentAt)) // Oldest first
print(snapshot.lastFailure?.reason) // nil once a request succeeds
print(snapshot.activity.map(\.logged)) // Events logged each minute over the last hour

for await snapshot in telemetry.debugUpdates() {
    // Called right away, then each time events are logged, sent, or fail to send.
}
```

```kotlin
// Kotlin
val snapshot = telemetry.debugSnapshot() // Suspends.
println(snapshot.pending.map { it.name }) // Oldest first, the order they're sent in
println(snapshot.sent.map { it.sentAtMillis }) // Oldest first
println(snapshot.lastFailure?.reason) // null once a request succeeds
println(snapshot.activity.map { it.logged }) // Events logged each minute over the last hour

val listener = telemetry.addDebugListener { snapshot ->
    // Called right away, then each time events are logged, sent, or fail to send,
    // on a background thread.
}
listener.close()
```

- Pending events are read from disk, so they include events left over from previous launches, and events in requests that haven't finished.
- Sent events include events the server rejected, with an outcome of rejected and the status code. They're kept in memory only, up to `debugHistoryLimit` (default 200), oldest dropped first. Set it to `0` to keep none, and call `clearDebugHistory()` to empty it.
- `activity` counts the events logged and sent in each minute of the last hour, leaving out quiet minutes. It includes events left over from earlier launches, counted as logged when they were logged. It's kept in memory regardless of `debugHistoryLimit`, and `clearDebugHistory()` doesn't empty it.
- `sessionStarts` lists when each session's first event was logged, for sessions that started in the last hour, including sessions from earlier launches whose events were still pending.
- The instance's session ID, `sessionID` in Swift and `sessionId` in Kotlin, is the ID sent with this launch's events, so you can find them on your server.
