# Configuration

Only `endpoint` is required. The options have the same names on every platform; where their values are spelled differently, the table shows Swift, then Kotlin.

| Option | Default | Description |
| --- | --- | --- |
| `endpoint` | None | The URL that events are sent to |
| `format` | `.jsonBatch` / `RequestFormat.JsonBatch` | How events are encoded into requests: JSON batches, one JSON request per event, or one request per event with every field in the URL; see [Request formats](backend.md#request-formats) |
| `environment` | `nil` / `null` | The kind of build, for example `debug`, `beta`, or `production`, sent as the session's `environment` to keep test data apart from real usage |
| `onInvalidEventName` | `.send` / `SEND` | What to do with event names that aren't lower snake case; see [Consistent event names](logging-events.md#consistent-event-names) |
| `maxEventSize` | `2048` | The largest event in bytes, measured as its query string URL; larger events are replaced with an error; see [Event size](logging-events.md#event-size) |
| `logsAppLifecycleEvents` | `false` | Log `app_foregrounded` and `app_backgrounded` breadcrumbs; see [Foreground and background](logging-events.md#foreground-and-background) |
| `headers` | Empty | Extra HTTP header fields for every request, for example `Authorization` |
| `batchSize` | `100` | The number of pending events that triggers a send; with JSON batches, also the most events in one request. Set it to `1` to send each event as soon as it's logged |
| `debounceInterval` | 10 seconds | How long to wait after the most recent event before sending a partial batch |
| `maxEventAge` | 30 days | How old unsent events can get before they're discarded |
| `storageDirectory` | A platform default | Where pending events are stored; see [iOS and macOS](setup-apple.md#how-it-behaves-on-apple-platforms) and [Android](setup-android.md#how-it-behaves-on-android). Use a separate directory for each instance |
| `debugHistoryLimit` | `200` | How many sent events, and how many requests, to keep in memory for the debug screen and snapshots; `0` keeps none; see [Debugging events](debugging.md) |

The JSON event format, `.jsonEvent(maxConcurrentRequests:)` / `RequestFormat.JsonEvent(maxConcurrentRequests)`, has one setting of its own: `maxConcurrentRequests`, how many requests can be in flight at once (default 4).

The query string format has two settings of its own: the HTTP `method`, `POST` (default) or `GET`, and `maxConcurrentRequests`, how many requests can be in flight at once (default 4).

## A full configuration

Each example app sets every option, with a comment explaining each one: [`Example/Davy/MyApp.swift`](../Example/Davy/MyApp.swift) and [`Android/example/…/ExampleApplication.kt`](../Android/example/src/main/kotlin/com/gu/davy/example/ExampleApplication.kt). In short:

```swift
// Swift
let telemetry = Davy(configuration: DavyConfiguration(
    endpoint: URL(string: "https://api.example.com/events")!,
    format: .queryString(method: .post, maxConcurrentRequests: 4),
    environment: "production",
    onInvalidEventName: .sendError,
    maxEventSize: 2048,
    logsAppLifecycleEvents: true,
    headers: ["Authorization": "Bearer <token>"],
    batchSize: 20,
    debounceInterval: .seconds(5),
    maxEventAge: .seconds(7 * 24 * 60 * 60),
    storageDirectory: DavyConfiguration.defaultStorageDirectory,
    debugHistoryLimit: 200
))
```

```kotlin
// Kotlin
val telemetry = Davy(context, DavyConfiguration(
    endpoint = URI("https://api.example.com/events"),
    format = RequestFormat.QueryString(method = HttpMethod.POST, maxConcurrentRequests = 4),
    environment = "production",
    onInvalidEventName = InvalidEventNameAction.SEND_ERROR,
    maxEventSize = 2048,
    logsAppLifecycleEvents = true,
    headers = mapOf("Authorization" to "Bearer <token>"),
    batchSize = 20,
    debounceInterval = 5.seconds,
    maxEventAge = 7.days,
    storageDirectory = Davy.defaultStorageDirectory(context),
    debugHistoryLimit = 200,
))
```

## One instance per storage directory

Each instance starts a new session and owns the events in its `storageDirectory`, so creating several that share a directory can send events twice. Create one instance when your app launches, and keep it for the app's lifetime.
