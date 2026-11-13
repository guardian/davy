# How delivery works

Delivery works the same way on every platform.

## When events are sent

- When `batchSize` events are pending (default 100). With JSON batches, each request contains at most `batchSize` events.
- When `debounceInterval` passes without a new event (default 10 seconds).
- When the app moves to the background. What counts as the background differs by platform: see [iOS and macOS](setup-apple.md#how-it-behaves-on-apple-platforms) and [Android](setup-android.md#how-it-behaves-on-android).
- On launch, for any events left over from a previous session.
- When `flush()` or `flushAndWait()` is called.

The debounce timer restarts with every event, so in a busy session events can wait until `batchSize` are pending or the app moves to the background. Lower `debounceInterval` and `batchSize` if you need events sooner.

## Delivery guarantees

- Every event is written to disk before it's sent, so events survive crashes, relaunches, and offline use.
- Events are sent oldest first. JSON batches are sent one request at a time. The query string format sends up to `maxConcurrentRequests` at once, so events can arrive out of order; use `timestamp` to order them.
- Events are removed only once the server accepts or permanently rejects them. Network errors and other responses keep them for a retry.
- Delivery is at least once. If a response is lost, for example because the app quits or the connection drops after the server stored the events, they're sent again with the same IDs.
- Breadcrumbs and errors share one queue, so they're stored, retried, and expired together and keep their relative order.

Events are discarded without being delivered when:

- Their name is invalid and `onInvalidEventName` is set to log an error or ignore them.
- They're over `maxEventSize`, and so is the error that would replace them.
- They're older than `maxEventAge` (default 30 days).
- The server permanently rejects them; see below.
- They can't be written to or read from disk, for example because the disk is full. An error is logged to the console or Logcat.

## Responses and retries

| Response | What happens |
| --- | --- |
| 2xx | Events are removed |
| 429 or 503 with `Retry-After` | All sending pauses for that long, even when flushing or relaunching, then resumes with a single request |
| 400, 413, 414, 422, or 431 | Events are discarded, since the server will never accept them, so they don't block the queue |
| Anything else, or a network error | Events are kept. No new requests start; retries use exponential backoff with jitter (up to 5 seconds, doubling to at most 5 minutes), starting with a single request |

`Retry-After` can be a number of seconds or an HTTP date, which is measured from the response's `Date` header if present, so a wrong device clock doesn't matter. Pauses are at least 1 second and at most 24 hours, lengthened by up to 10% at random so devices don't all return at once. `flush()`, `flushAndWait()`, and backgrounding skip backoff after errors, but never a `Retry-After` pause.

401, 403, and 404 are retried rather than discarded, since they usually mean the endpoint is misconfigured. A 429 or 503 without a valid `Retry-After` is retried with backoff.
