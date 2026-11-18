# Your backend

Davy is only the client. You run the endpoint, and what's behind it is up to you: a small web service that writes to a database, a serverless function, or a CDN that logs each request straight to a data warehouse. Only the endpoint URL is built into your app, so you can change everything behind it without an app update.

Every platform sends exactly the same requests, defined in an OpenAPI 3.1 document, [`Spec/openapi.yaml`](../Spec/openapi.yaml). Both clients' tests check their requests against it. Use it to check what your endpoint receives, generate server code, or browse the formats in any OpenAPI viewer. To see real requests from your app before your endpoint is ready, send them to the [test server](development.md#trying-the-clients-against-a-server).

Your endpoint should:

1. **Accept `POST` requests with a JSON body;** see [Request format](#request-format).
2. **Respond with 2xx once the events are stored.** The response body is ignored.
3. **Remove duplicates by event ID** (`id`), since [delivery is at least once](delivery.md#delivery-guarantees).
4. **Respond with 400, 413, 414, 422, or 431** to requests it will never accept, so their events are discarded instead of retried.
5. **Respond with 429 or 503 and `Retry-After`** to slow down or pause clients, for example during an incident or a cost spike.
6. **Expect late events.** Devices send events when they come back online, up to `maxEventAge` (default 30 days) after they were logged, so the most recent minutes of data are always incomplete. Allow for this in dashboards and alerts.
7. **Record when it received each request**, if you need a trusted time. `timestamp` and `sentAt` come from the device's clock, which can be wrong. Comparing `sentAt` with when you received the request estimates how far off the clock is; see [Fixing clock differences](#fixing-clock-differences).
8. **Treat the endpoint as public.** Anything built into an app, including an `Authorization` header, can be extracted from it, so validate what you receive. Ignore fields you don't recognize rather than rejecting them, since rejected events are discarded.
9. **Not log IP addresses**, if you want to keep reports anonymous.

## Request format

Events are sent as a `POST` to the endpoint with `Content-Type: application/json`, `User-Agent: Davy`, `Accept-Language: *`, and any other headers in `headers` except `Cookie` and `Cookie2`. Requests never use cookies or the local cache, and redirects are not followed. Configure the final HTTPS URL directly.

Each request contains between 1 and `batchSize` events from a single session, along with common app and device data captured when that session started:

```json
{
  "sentAt": "2026-11-09T11:39:00.250Z",
  "session": {
    "id": "6F9619FF-8B86-D011-B42D-00C04FC964FF",
    "environment": "production",
    "appVersion": "1.2.0",
    "build": "45",
    "os": "iOS",
    "osVersion": "26.1.0",
    "deviceModel": "iPhone17,1",
    "locale": "en_GB",
    "info": {
      "edition": "uk"
    }
  },
  "events": [
    { "id": "1B4E28BA-2FA1-11D2-883F-0016D3CCA427", "type": "breadcrumb", "name": "checkout_started", "timestamp": "2026-11-09T11:38:55.780Z" },
    {
      "id": "9C5B94B1-35AD-49BB-B118-8E8FC24ABF80",
      "type": "error",
      "name": "payment_declined",
      "info": {
        "reason": "card_expired",
        "cpu_percent": 12,
        "memory_used_mb": 143,
        "memory_available_mb": 2650,
        "thermal_state": "nominal",
        "low_power_mode": false
      },
      "timestamp": "2026-11-09T11:38:57.112Z"
    }
  ]
}
```

Set `batchSize` to `1` to send each event as its own request with a single-element `events` array.

## Session fields

| Field | Description |
| --- | --- |
| `id` | A random UUID generated when the instance is created, typically once per launch |
| `environment` | The configured `environment`, for example `debug`, `beta`, or `production`; omitted if not set |
| `appVersion` | The app's version: `CFBundleShortVersionString` on Apple platforms, `versionName` on Android; omitted if missing |
| `build` | The app's build number: `CFBundleVersion` on Apple platforms, `versionCode` on Android; omitted if missing |
| `os` | `iOS`, `macOS`, `macCatalyst`, `visionOS`, `tvOS`, `watchOS`, or `Android` |
| `osVersion` | `major.minor.patch`, for example `26.1.0`, or `15.0.0` for Android 15 |
| `deviceModel` | The hardware model identifier on Apple platforms, such as `iPhone17,1`, or `Simulator`; the model name on Android, such as `Pixel 9` |
| `locale` | The device's language and region only, for example `en_GB`, or just the language if there's no region |
| `info` | The configured `info` and any entries added with `addSessionInfo`, as [info values](#info-values), for additional app and session details; omitted if empty |

The session ID is kept in memory and never reused, so it links events within a session without identifying the user or device across sessions. Events left over from a previous launch are sent in their own request with their original session data, so they're attributed to the app and OS version they were logged in, even after an update.

## Event fields

| Field | Description |
| --- | --- |
| `id` | A random UUID generated when the event is logged and sent unchanged with every retry, so the server can discard duplicates |
| `type` | `breadcrumb` or `error` |
| `name` | The name the event was logged with |
| `info` | The event's details as [info values](#info-values), with [diagnostics](#diagnostics) added for errors; omitted if empty |
| `timestamp` | When the event was logged, by the device's clock, as ISO 8601 in UTC with milliseconds |

## Request fields

Wire field names use camelCase, so the send time is `sentAt`, not `sent_at`.
Like event timestamps, it is ISO 8601 in UTC with exactly three millisecond digits
(including `.000Z` for whole seconds), but records the send time rather than the event time.

| Field | Description |
| --- | --- |
| `sentAt` | When the request was sent, by the device's clock, in the same format as `timestamp`. Set again for every attempt, so a retry has a later `sentAt`; the same for every event in a request |

## Fixing clock differences

A device's clock can be wrong, so `timestamp` can be minutes or even years out. `sentAt` is read from the same clock when the request is sent, so the difference between your server's time when it receives the request and `sentAt` is roughly how far the clock is off, plus the time the request took to arrive:

```
clockOffset = receivedAt − sentAt
trustedTimestamp = timestamp + clockOffset
```

Store `sentAt` and `receivedAt` alongside each event and apply the correction when you query, rather than overwriting `timestamp`, so you can change your approach later. The estimate is rough for a request that took a long time to arrive, and only holds if the clock didn't change between logging the event and sending it.

## Diagnostics

Added to each error's `info` when it's logged, to help explain it, for example by showing that an error only happens when the app is low on memory or the device is overheating. They replace any values already in `info` under the same keys. Breadcrumbs don't include them.

The keys are the same on every platform, but the values come from each platform and mean slightly different things, so compare them within a platform:

| Key | iOS and macOS | Android |
| --- | --- | --- |
| `cpu_percent` | The app's CPU usage as a percentage of one core, summed across its threads, so it can be over 100. The system averages each thread's usage over the last few seconds | CPU time used by all the app's threads as a percentage of one core, so it can be over 100. Averaged since the previous error at least a second earlier, or since the instance was created |
| `memory_used_mb` | The app's memory footprint, as shown by Xcode's memory gauge | The app's Java and native heap use |
| `memory_available_mb` | How much more the app can use before the system terminates it; omitted where there's no limit, such as on macOS or in Simulator | How much more the Java heap can grow before the app runs out of memory |
| `thermal_state` | `nominal`, `fair`, `serious`, or `critical` | `none`, `light`, `moderate`, `severe`, `critical`, `emergency`, or `shutdown`; omitted before Android 10 |
| `low_power_mode` | Whether Low Power Mode is on, as a Boolean | Whether Battery Saver is on, as a Boolean |

CPU and memory are whole numbers, and memory is in whole megabytes. Each key except `low_power_mode` is omitted if its value can't be read.

## Info values

Each `info` value is a JSON string, number, or Boolean, matching the type the app logged, so `"attempt": 2` and `"attempt": "2"` are different values. Store them so you can query numbers as numbers, for example in a JSON column. Numbers beyond ±2^53 may lose precision. A non-finite number is sent as the string `"inf"`, `"-inf"`, or `"nan"`. Davy's own keys are typed too: `duration_ms` and `size` are integers, and diagnostics are as described above.

Versions before 2.0.0 of the spec sent every value as a string.
