# Configuration

Only `endpoint` is required. The options have the same names on every platform; where their values are spelled differently, the table shows Swift, then Kotlin.

| Option | Default | Description |
| --- | --- | --- |
| `endpoint` | None | The URL that events are sent to |
| `environment` | `nil` / `null` | The kind of build, for example `debug`, `beta`, or `production`, sent as the session's `environment` to keep test data apart from real usage |
| `info` | Empty | Additional details about the app and session, sent with every event in `session.info`; can be updated later with [`addSessionInfo`](logging-events.md#updating-session-info) |
| `onInvalidEventName` | `.send` / `SEND` | What to do with event names that aren't lower snake case; see [Consistent event names](logging-events.md#consistent-event-names) |
| `maxEventSize` | `4096` | The largest event in bytes, measured as a single-event JSON request body; larger events are replaced with an error; see [Event size](logging-events.md#event-size) |
| `logsAppLifecycleEvents` | `false` | Log `app_foregrounded` and `app_backgrounded` breadcrumbs; see [Foreground and background](logging-events.md#foreground-and-background) |
| `headers` | Empty | Extra HTTP header fields for every request, for example `Authorization`; `Content-Type`, `User-Agent`, and `Accept-Language` are always set by Davy, and `Cookie` and `Cookie2` are ignored |
| `allowsInsecureHTTP` (Apple), `allowsInsecureHttp` (Android) | `false` | Allow plain HTTP for development, subject to platform network policy. Leave disabled in production |
| `batchSize` | `100` | The number of pending events that triggers a send, and the most events in one request. Set it to `1` to send each event as soon as it's logged |
| `debounceInterval` | 10 seconds | How long to wait after the most recent event before sending a partial batch |
| `maxEventAge` | 30 days | How old unsent events can get before they're discarded |
| `storageDirectory` | A platform default | Where pending events are stored; see [iOS and macOS](setup-apple.md#how-it-behaves-on-apple-platforms) and [Android](setup-android.md#how-it-behaves-on-android). Use a separate directory for each instance |
| `sendsEvents` | `true` | Whether events are sent from launch. When `false`, events are still logged, stored, and shown on the debug screen, but nothing is sent until `start()` is called; see [Turning sending off](delivery.md#turning-sending-off) |
| `debugHistoryLimit` | `200` | How many sent events, and how many requests, to keep in memory for the debug screen and snapshots; `0` keeps none; see [Debugging events](debugging.md) |

## A full configuration

Each example app sets every option, with a comment explaining each one: [`Example/Davy/MyApp.swift`](../Example/Davy/MyApp.swift) and [`Android/example/…/ExampleApplication.kt`](../Android/example/src/main/kotlin/com/gu/davy/example/ExampleApplication.kt). In short:

```swift
// Swift
let telemetry = Davy(configuration: DavyConfiguration(
    endpoint: URL(string: "https://api.example.com/events")!,
    environment: "production",
    info: ["edition": "uk"],
    onInvalidEventName: .sendError,
    maxEventSize: 4096,
    logsAppLifecycleEvents: true,
    headers: ["Authorization": "Bearer <token>"],
    batchSize: 20,
    debounceInterval: .seconds(5),
    maxEventAge: .seconds(7 * 24 * 60 * 60),
    storageDirectory: DavyConfiguration.defaultStorageDirectory,
    debugHistoryLimit: 200,
    sendsEvents: true
))
```

```kotlin
// Kotlin
val telemetry = Davy(context, DavyConfiguration(
    endpoint = URI("https://api.example.com/events"),
    environment = "production",
    info = mapOf("edition" to "uk"),
    onInvalidEventName = InvalidEventNameAction.SEND_ERROR,
    maxEventSize = 4096,
    logsAppLifecycleEvents = true,
    headers = mapOf("Authorization" to "Bearer <token>"),
    batchSize = 20,
    debounceInterval = 5.seconds,
    maxEventAge = 7.days,
    storageDirectory = Davy.defaultStorageDirectory(context),
    debugHistoryLimit = 200,
    sendsEvents = true,
))
```

## One instance per storage directory

Each instance starts a new session and owns the events in its `storageDirectory`, so creating several that share a directory can send events twice. Create one instance when your app launches, and keep it for the app's lifetime.
