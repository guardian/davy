# How delivery works

Queued events are tied to the exact endpoint URL they were logged for. If you reuse the storage directory with a different endpoint, Davy discards events for the old URL rather than sending them to the new one. Pending events written by older versions without an endpoint binding are also discarded. Redirects are treated as failed delivery attempts, so configure the final URL directly.

Delivery works the same way on every platform.

## When events are sent

- When `batchSize` events are pending (default 100). Each request contains at most `batchSize` events.
- When `debounceInterval` passes without a new event (default 10 seconds).
- When the app moves to the background. What counts as the background differs by platform: see [iOS and macOS](setup-apple.md#how-it-behaves-on-apple-platforms) and [Android](setup-android.md#how-it-behaves-on-android).
- On launch, for any events left over from a previous session.
- When `flush()` or `flushAndWait()` is called.

None of these send anything unless the instance is in full mode; see [Turning Davy off](#turning-davy-off).

The debounce timer restarts with every event, so in a busy session events can wait until `batchSize` are pending or the app moves to the background. Lower `debounceInterval` and `batchSize` if you need events sooner; with a `batchSize` of 1, each event is sent as soon as it's logged.

## Turning Davy off

Each instance is in one of three modes:

| Mode | Swift, Kotlin | Stores events | Sends events |
| --- | --- | --- | --- |
| Full (the default) | `.full`, `FULL` | Yes | Yes |
| Local only | `.localOnly`, `LOCAL_ONLY` | Yes, and shows them on the debug screen | No |
| Off | `.off`, `OFF` | No | No |

Set the mode an instance starts in with `initialMode`, and change it with `start()` and `stop()`, for example from remote config as a kill switch, or when the user gives or withdraws consent:

```swift
telemetry.stop()                 // Local only: events are still logged and stored, but not sent
telemetry.stop(deleteAll: true)  // Off: deletes every stored event, and stores no more
telemetry.start()                // Full: sends anything stored, then carries on as normal
```

In Kotlin, it's `telemetry.stop(deleteAll = true)`.

While not in full mode, `flush()` and `flushAndWait()` return without sending. A request already in flight when `stop()` is called finishes, but no more are made.

- **Local only.** Events stored while stopped are sent once `start()` is called, unless they're older than `maxEventAge` by then, and only the newest `maxPendingEvents` are kept.
- **Off.** `log` does nothing, so events logged while off are lost, but `addSessionInfo` still updates the session for events logged later. `stop(deleteAll: true)` deletes every event waiting to be sent, including any in a request in flight so it can't be retried, and the debug history of sent events and requests. Starting an instance in off mode with `initialMode` doesn't delete anything: events stored by an earlier launch are kept until sending starts or they expire, so call `stop(deleteAll: true)` to delete them.

The mode isn't remembered across launches, so set `initialMode` each launch from your remote config's or consent manager's cached value.

## Delivery guarantees

- Every event is written to disk before it's sent, so events survive crashes, relaunches, and offline use.
- Events are sent oldest first, one request at a time.
- Events are removed only once the server accepts or permanently rejects them. Network errors and other responses keep them for a retry.
- Delivery is at least once. If a response is lost, for example because the app quits or the connection drops after the server stored the events, they're sent again with the same IDs.
- Breadcrumbs and errors share one queue, so they're stored, retried, and expired together and keep their relative order.

Events are discarded without being delivered when:

- Their name is invalid and `onInvalidEventName` is set to log an error or ignore them.
- They're over `maxEventSize`, and so is the error that would replace them.
- They're older than `maxEventAge` (default 30 days).
- They're logged while the instance is off, or deleted with `stop(deleteAll: true)`; see [Turning Davy off](#turning-davy-off).
- More than `maxPendingEvents` (default 1,000) are waiting to be sent, for example after a long outage or while sending is stopped. The oldest are discarded first, and an error is logged the first time.
- The server permanently rejects them; see below.
- They can't be written to disk, for example because the disk is full, or their file is later found empty or missing. An error is logged to the console or Logcat. A file that exists but can't be read for now, for example because data protection locks it while the device is locked, is kept and read again later.

## Responses and retries

| Response | What happens |
| --- | --- |
| 2xx | Events are removed |
| 429 or 503 with `Retry-After` | All sending pauses for that long, even when flushing or relaunching |
| 400, 413, 414, 422, or 431 | Events are discarded, since the server will never accept them, so they don't block the queue |
| Anything else, or a network error | Events are kept. Sending stops; retries use exponential backoff with jitter (up to 5 seconds, doubling to at most 5 minutes) |

`Retry-After` can be a number of seconds or an HTTP date, which is measured from the response's `Date` header if present, so a wrong device clock doesn't matter. Pauses are at least 1 second and at most 24 hours, lengthened by up to 10% at random so devices don't all return at once. `flush()`, `flushAndWait()`, and backgrounding skip backoff after errors, but never a `Retry-After` pause.

401, 403, and 404 are retried rather than discarded, since they usually mean the endpoint is misconfigured. A 429 or 503 without a valid `Retry-After` is retried with backoff.
