# Your backend

Davy is only the client. You run the endpoint, and what's behind it is up to you: a small web service that writes to a database, a serverless function, or a CDN that logs each request straight to a data warehouse. Only the endpoint URL is built into your app, so you can change everything behind it without an app update.

Every platform sends exactly the same requests, defined in an OpenAPI 3.1 document, [`Spec/openapi.yaml`](../Spec/openapi.yaml). Both clients' tests check their requests against it. Use it to check what your endpoint receives, generate server code, or browse the formats in any OpenAPI viewer.

Your endpoint should:

1. **Accept your chosen [request format](#request-formats).**
2. **Respond with 2xx once the events are stored.** The response body is ignored.
3. **Remove duplicates by event ID** (`id`, or `eventId` in the query string format), since [delivery is at least once](delivery.md#delivery-guarantees).
4. **Respond with 400, 413, 414, 422, or 431** to requests it will never accept, so their events are discarded instead of retried.
5. **Respond with 429 or 503 and `Retry-After`** to slow down or pause clients, for example during an incident or a cost spike.
6. **Expect late events.** Devices send events when they come back online, up to `maxEventAge` (default 30 days) after they were logged, so the most recent minutes of data are always incomplete. Allow for this in dashboards and alerts.
7. **Record when it received each request**, if you need a trusted time. `timestamp` comes from the device's clock, which can be wrong.
8. **Treat the endpoint as public.** Anything built into an app, including an `Authorization` header, can be extracted from it, so validate what you receive. Ignore fields you don't recognize rather than rejecting them, since rejected events are discarded.
9. **Not log IP addresses**, if you want to keep reports anonymous.

## Request formats

Set `format` to choose how events are encoded:

| Format | Requests |
| --- | --- |
| JSON batch (default) | Up to `batchSize` events per `POST`, as a JSON body |
| Query string | One request per event with an empty body and every field in the URL; `POST` (default) or `GET`, up to 4 in flight by default |

Every request includes the headers in `headers`. Requests never use cookies or the local cache.

### JSON batch

A `POST` to the endpoint with `Content-Type: application/json`. Each request contains events from a single session, along with common app and device data captured when that session started:

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

### Query string

For servers that can log fields from the URL but not from the body, such as a CDN logging requests straight to a data warehouse. The event's name is appended to the endpoint's path, and every other field is a query parameter, omitted if it has no value:

```
POST https://api.example.com/events/payment_declined?eventId=9C5B94B1-…&type=error&timestamp=2026-11-09T11%3A38%3A57.112Z&sessionId=6F9619FF-…&environment=production&appVersion=1.2.0&build=45&os=Android&osVersion=15.0.0&deviceModel=Pixel%209&locale=en_GB&info=%7B%22cpu_percent%22%3A%2212%22%2C%22low_power_mode%22%3A%22false%22%2C%22memory_available_mb%22%3A%22190%22%2C%22memory_used_mb%22%3A%22143%22%2C%22reason%22%3A%22card_expired%22%2C%22thermal_state%22%3A%22none%22%7D
```

- Parameters are `eventId`, `type`, `timestamp`, and `info` from the event, and `sessionId`, `environment`, `appVersion`, `build`, `os`, `osVersion`, `deviceModel`, and `locale` from the session, in that order.
- `info` is a JSON object string with sorted keys.
- Everything except `A–Z a–z 0–9 - . _ ~` is percent-encoded, including `+`, so values decode the same way on every server.
- Query items already in the endpoint are kept.
- With `GET`, the server should respond with `Cache-Control: no-store` so proxies don't cache it.
- URLs are at most `maxEventSize` bytes (default 2,048); see [Event size](logging-events.md#event-size). If you raise it, note that a server rejecting a URL as too long with 414 causes the event to be discarded.

## Session fields

| Field | Description |
| --- | --- |
| `id` | A random UUID generated when the instance is created, typically once per launch; `sessionId` in the query string format |
| `environment` | The configured `environment`, for example `debug`, `beta`, or `production`; omitted if not set |
| `appVersion` | The app's version: `CFBundleShortVersionString` on Apple platforms, `versionName` on Android; omitted if missing |
| `build` | The app's build number: `CFBundleVersion` on Apple platforms, `versionCode` on Android; omitted if missing |
| `os` | `iOS`, `macOS`, `macCatalyst`, `visionOS`, `tvOS`, `watchOS`, or `Android` |
| `osVersion` | `major.minor.patch`, for example `26.1.0`, or `15.0.0` for Android 15 |
| `deviceModel` | The hardware model identifier on Apple platforms, such as `iPhone17,1`, or `Simulator`; the model name on Android, such as `Pixel 9` |
| `locale` | The device's language and region only, for example `en_GB`, or just the language if there's no region |

The session ID is kept in memory and never reused, so it links events within a session without identifying the user or device across sessions. Events left over from a previous launch are sent in their own request with their original session data, so they're attributed to the app and OS version they were logged in, even after an update.

## Event fields

| Field | Description |
| --- | --- |
| `id` | A random UUID generated when the event is logged and sent unchanged with every retry, so the server can discard duplicates; `eventId` in the query string format |
| `type` | `breadcrumb` or `error` |
| `name` | The name the event was logged with; part of the path in the query string format |
| `info` | The event's details as strings, with [diagnostics](#diagnostics) added for errors; omitted if empty |
| `timestamp` | When the event was logged, by the device's clock, as ISO 8601 in UTC with milliseconds |

## Diagnostics

Added to each error's `info` when it's logged, to help explain it, for example by showing that an error only happens when the app is low on memory or the device is overheating. They replace any values already in `info` under the same keys. Breadcrumbs don't include them.

The keys are the same on every platform, but the values come from each platform and mean slightly different things, so compare them within a platform:

| Key | iOS and macOS | Android |
| --- | --- | --- |
| `cpu_percent` | The app's CPU usage as a percentage of one core, summed across its threads, so it can be over 100. The system averages each thread's usage over the last few seconds | CPU time used by all the app's threads as a percentage of one core, so it can be over 100. Averaged since the previous error at least a second earlier, or since the instance was created |
| `memory_used_mb` | The app's memory footprint, as shown by Xcode's memory gauge | The app's Java and native heap use |
| `memory_available_mb` | How much more the app can use before the system terminates it; omitted where there's no limit, such as on macOS or in Simulator | How much more the Java heap can grow before the app runs out of memory |
| `thermal_state` | `nominal`, `fair`, `serious`, or `critical` | `none`, `light`, `moderate`, `severe`, `critical`, `emergency`, or `shutdown`; omitted before Android 10 |
| `low_power_mode` | Whether Low Power Mode is on, `true` or `false` | Whether Battery Saver is on, `true` or `false` |

Like all `info` values, they're strings, and memory is in whole megabytes. Each key except `low_power_mode` is omitted if its value can't be read.
