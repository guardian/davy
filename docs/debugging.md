# Debugging events

Each platform has a debug screen that lists the events waiting to be sent, the events sent since launch, and the requests they were sent in, so you can check what your app logs during development. It's a development tool, so its text is in English and isn't localized.

| Platform | Debug screen | How to show it |
| --- | --- | --- |
| iOS and macOS | `DavyDebugView` | `.davyDebugSheet(isPresented:)`, or present `DavyDebugView(telemetry:)`; see [setup](setup-apple.md#showing-the-debug-view) |
| Android | `DavyDebugActivity` | `DavyDebugActivity.start(context, telemetry)`; see [setup](setup-android.md#showing-the-debug-screen) |

## What it shows

- **A summary:** the current session ID, and how many events were logged since launch, including events left over from earlier launches, and how many of those are pending and sent. Every logged event is either pending or sent, so pending plus sent always equals logged. On Apple platforms, a chart shows how many events were logged and sent per minute over the last hour, with dotted lines where each session's first event was logged. Android has no built-in chart, so it shows the totals only.
- **The last failure:** if sending fails, the reason is shown at the top. Select it to see all of it.
- **The events:** pending and sent events together, most recently logged first, with a mark showing whether each is pending, sent, or rejected.
- **Search and filtering:** search by name, event or session `info` key or value, or event ID, ignoring case, and choose **Show** to list only pending, sent, rejected, or error events.
- **Event details:** select an event to see its ID, when it was logged, its status, its `info`, its session data, and a shareable single-event example in the request format. The example uses the event's timestamp for `sentAt`. Open a linked request to see the actual batch and send time.
- **The requests:** every request sent since launch, newest first, including requests that failed or got no response, with a mark showing whether the server accepted, rejected, or paused it, how many events it carried, its status code, and how long it took. Search by body. On Apple platforms, choose **Requests** at the top; on Android, select **Show Requests**.
- **Request details:** select a request to see when it was sent, how long it took, the response's status code and what it meant, its events, its headers, and its body as indented JSON, which you can share. Headers are shown as sent, including any `Authorization` header.
- **Actions:** send pending events now, and clear the sent history and requests.

## Snapshots

To build your own tools, read the same data with `debugSnapshot()`, or get a new snapshot each time it changes:

```swift
// Swift
let snapshot = await telemetry.debugSnapshot()
print(snapshot.pending.map(\.name)) // Oldest first, the order they're sent in
print(snapshot.sent.map(\.sentAt)) // Oldest first
print(snapshot.requests.map(\.statusCode)) // In the order they finished; nil if no response
print(snapshot.lastFailure?.reason) // nil once a request succeeds
print(snapshot.loggedCount) // Always snapshot.pending.count + snapshot.sentCount
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
println(snapshot.requests.map { it.statusCode }) // In the order they finished; null if no response
println(snapshot.lastFailure?.reason) // null once a request succeeds
println(snapshot.loggedCount) // Always snapshot.pending.size + snapshot.sentCount
println(snapshot.activity.map { it.logged }) // Events logged each minute over the last hour

val listener = telemetry.addDebugListener { snapshot ->
    // Called right away, then each time events are logged, sent, or fail to send,
    // on a background thread.
}
listener.close()
```

- Pending events are read from disk, so they include events left over from previous launches, and events in requests that haven't finished.
- Sent events include events the server rejected, with an outcome of rejected and the status code. They're kept in memory only, up to `debugHistoryLimit` (default 200), oldest dropped first. Set it to `0` to keep none, and call `clearDebugHistory()` to empty it.
- `requests` lists each request that was sent, in the order they finished, with the headers Davy set, its body, its events' IDs, when it was sent, how long it took, the response's status code, and its outcome: delivered, rejected, throttled with the server's `Retry-After`, or failed with the same reason as `lastFailure`. Requests that couldn't be built aren't listed. Like sent events, they're kept in memory only, up to `debugHistoryLimit`, and `clearDebugHistory()` empties them. Each keeps its body, so lower `debugHistoryLimit` if memory is tight.
- `loggedCount` counts the events logged since launch, plus the events left over from earlier launches. `sentCount` counts the events the server accepted or rejected since launch. Neither is limited by `debugHistoryLimit` or reset by `clearDebugHistory()`. Events discarded without being sent, because they were older than `maxEventAge` or couldn't be read, aren't counted, so `loggedCount` is always the number pending plus `sentCount`.
- `activity` counts the events logged and sent in each minute of the last hour, leaving out quiet minutes. It includes events left over from earlier launches, counted as logged when they were logged. It's kept in memory regardless of `debugHistoryLimit`, and `clearDebugHistory()` doesn't empty it.
- `sessionStarts` lists when each session's first event was logged, for sessions that started in the last hour, including sessions from earlier launches whose events were still pending.
- The instance's session ID, `sessionID` in Swift and `sessionId` in Kotlin, is the ID sent with this launch's events, so you can find them on your server.
