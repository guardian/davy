import Foundation

/// Configuration describing where and how ``Davy`` delivers events.
public struct DavyConfiguration: Sendable {
    /// The URL that events are sent to, in the configured ``format``.
    public var endpoint: URL

    /// How events are encoded into requests: in batches as a JSON body (the default), one request
    /// per event with a JSON body, or one request per event with every field in the URL.
    public var format: RequestFormat

    /// The kind of build sending events, for example `"debug"`, `"beta"`, or `"production"`, sent as
    /// the request's `session.environment`. Omitted when `nil`.
    ///
    /// Use it to separate test data from real usage. TestFlight and App Store builds share a binary,
    /// so telling them apart needs a runtime check or a separate build configuration.
    public var environment: String?

    /// Additional details about the app and session, sent with every event as the request's
    /// `session.info` (or `sessionInfo` query parameter). Omitted when empty.
    ///
    /// As with event ``Event/info``, don't include personal data or values that identify a person
    /// or device across sessions.
    public var info: [String: String]

    /// What ``Davy/log(_:)`` does with an event whose name isn't lower snake case, such as
    /// `"checkoutStarted"` or `"checkout-started"` instead of `"checkout_started"`.
    ///
    /// A common setup is ``InvalidEventNameAction/crash`` in debug builds, so inconsistent names are
    /// caught during development, and ``InvalidEventNameAction/send`` or
    /// ``InvalidEventNameAction/ignore`` in release builds.
    public var onInvalidEventName: InvalidEventNameAction

    /// The largest an event can be, in bytes, measured as the length of the URL it's sent to with
    /// ``RequestFormat/queryString(method:maxConcurrentRequests:)``, including the endpoint,
    /// session data, and an error's diagnostics.
    ///
    /// Applies whatever the ``format``, so any event can be sent as a URL, and a single large event
    /// can't make a whole ``RequestFormat/jsonBatch`` request too large for the server to accept.
    ///
    /// An event over this size is replaced with the error `"davy_event_too_large"`, with its
    /// name in `info` under `"rejected_name"` and its size under `"size"`; the original event's
    /// `info` isn't sent. If the error is also over this size, for example because the name is very
    /// long, nothing is sent.
    ///
    /// The default, 2,048 bytes, is within the default URL limits of common servers, proxies, and
    /// CDNs. Raise it only if everything between the app and your endpoint accepts longer URLs.
    public var maxEventSize: Int
    /// Whether to log a breadcrumb named `"app_backgrounded"` each time the app moves to the
    /// background (iOS) or resigns active (macOS), and `"app_foregrounded"` each time it returns.
    ///
    /// `"app_backgrounded"` is logged before pending events are flushed, so it's sent right away.
    /// Launching isn't logged as `"app_foregrounded"`, so every `"app_foregrounded"` follows an
    /// `"app_backgrounded"` in the same session. Like the automatic flush, this doesn't apply to
    /// app extensions.
    public var logsAppLifecycleEvents: Bool

    /// Additional HTTP header fields sent with every request, for example an `Authorization` header.
    public var headers: [String: String]

    /// The number of pending events that triggers an immediate send. With
    /// ``RequestFormat/jsonBatch``, also the maximum number of events included in a single request.
    /// Values below 1 are treated as 1.
    ///
    /// To send each event as soon as it's logged, set it to 1.
    public var batchSize: Int

    /// How long to wait after the most recent event before sending a partially filled batch.
    public var debounceInterval: Duration

    /// Events older than this are discarded without being sent.
    public var maxEventAge: Duration

    /// The directory where pending events are stored.
    ///
    /// Each ``Davy`` instance should use its own directory.
    public var storageDirectory: URL

    /// How many sent events, and how many requests, to keep in memory for
    /// ``Davy/debugSnapshot()`` and ``DavyDebugView``. The oldest are dropped once there are more.
    /// `0` keeps none.
    ///
    /// The history isn't stored on disk, so it starts empty each time an instance is created. Each
    /// request is kept with its body, so lower this if memory is tight.
    public var debugHistoryLimit: Int

    /// Creates a configuration.
    ///
    /// - Parameters:
    ///   - endpoint: The URL that events are sent to.
    ///   - format: How events are encoded into requests. Defaults to ``RequestFormat/jsonBatch``.
    ///   - environment: The kind of build sending events, for example `"debug"`, `"beta"`, or `"production"`.
    ///   - info: Additional details sent with every event in `session.info`.
    ///   - onInvalidEventName: What to do with events whose names aren't lower snake case. Defaults to
    ///     ``InvalidEventNameAction/send``.
    ///   - maxEventSize: The largest an event can be, in bytes, as a query string URL. Defaults to 2,048.
    ///   - logsAppLifecycleEvents: Whether to log `"app_foregrounded"` and `"app_backgrounded"`
    ///     breadcrumbs. Defaults to `false`.
    ///   - headers: Additional HTTP header fields sent with every request.
    ///   - batchSize: The number of pending events that triggers a send. Defaults to 100.
    ///   - debounceInterval: How long to wait after the most recent event before sending. Defaults to 10 seconds.
    ///   - maxEventAge: How long unsent events are kept. Defaults to 30 days.
    ///   - storageDirectory: Where events are persisted. Defaults to ``defaultStorageDirectory``.
    ///   - debugHistoryLimit: How many sent events and requests to keep in memory for debugging. Defaults to 200.
    public init(
        endpoint: URL,
        format: RequestFormat = .jsonBatch,
        environment: String? = nil,
        info: [String: String] = [:],
        onInvalidEventName: InvalidEventNameAction = .send,
        maxEventSize: Int = 2048,
        logsAppLifecycleEvents: Bool = false,
        headers: [String: String] = [:],
        batchSize: Int = 100,
        debounceInterval: Duration = .seconds(10),
        maxEventAge: Duration = .seconds(30 * 24 * 60 * 60),
        storageDirectory: URL = DavyConfiguration.defaultStorageDirectory,
        debugHistoryLimit: Int = 200
    ) {
        self.endpoint = endpoint
        self.format = format
        self.environment = environment
        self.info = info
        self.onInvalidEventName = onInvalidEventName
        self.maxEventSize = maxEventSize
        self.logsAppLifecycleEvents = logsAppLifecycleEvents
        self.headers = headers
        self.batchSize = batchSize
        self.debounceInterval = debounceInterval
        self.maxEventAge = maxEventAge
        self.storageDirectory = storageDirectory
        self.debugHistoryLimit = debugHistoryLimit
    }

    /// `Application Support/<bundle identifier>/Davy`.
    public static var defaultStorageDirectory: URL {
        URL.applicationSupportDirectory
            .appending(path: Bundle.main.bundleIdentifier ?? "Davy", directoryHint: .isDirectory)
            .appending(path: "Davy", directoryHint: .isDirectory)
    }
}

extension DavyConfiguration {
    /// How events are encoded into HTTP requests.
    public enum RequestFormat: Sendable, Hashable {
        /// Events are `POST`ed in batches of up to ``DavyConfiguration/batchSize`` as a JSON
        /// body, `{ "session": { … }, "events": [ … ] }`, one request at a time.
        case jsonBatch

        /// Each event is `POST`ed as its own request, with the same JSON body as ``jsonBatch``
        /// containing exactly one event: `{ "session": { … }, "events": [ { … } ] }`.
        ///
        /// Suits servers that handle one event per request, and keeps one rejected event from
        /// discarding a whole batch. The same endpoint handles both formats, so switching between
        /// them needs no server changes.
        ///
        /// - Parameter maxConcurrentRequests: How many requests can be in flight at once, so
        ///   events can arrive out of order. Values below 1 are treated as 1. Defaults to 4.
        case jsonEvent(maxConcurrentRequests: Int = 4)

        /// Each event is sent as its own request with an empty body. The event's name is appended
        /// to the endpoint's path, and every other field is a query parameter:
        /// `<endpoint>/checkout_started?eventId=…&type=breadcrumb&timestamp=…&sentAt=…&sessionId=…`.
        ///
        /// Suits servers that can log fields from the URL but not from the body, such as a CDN
        /// that logs requests straight to a data warehouse.
        ///
        /// - Parameters:
        ///   - method: The HTTP method. Defaults to `POST`, which the system and proxies never
        ///     cache. `GET` requests are sent with local caching disabled, but the server should
        ///     still respond with `Cache-Control: no-store`.
        ///   - maxConcurrentRequests: How many requests can be in flight at once. Values below 1
        ///     are treated as 1. Defaults to 4.
        case queryString(method: HTTPMethod = .post, maxConcurrentRequests: Int = 4)
    }

    /// The HTTP method used by ``RequestFormat/queryString(method:maxConcurrentRequests:)``.
    public enum HTTPMethod: String, Sendable, Hashable {
        case get = "GET"
        case post = "POST"
    }

    /// What ``Davy/log(_:)`` does with an event whose name isn't lower snake case.
    ///
    /// A lower snake case name is lowercase ASCII letters and digits in words separated by single
    /// underscores, starting with a letter, for example `"checkout_started"` or `"step_2_completed"`.
    public enum InvalidEventNameAction: Sendable, Hashable {
        /// Stops the app with `fatalError` at the `log(_:)` call, so the name is fixed before it ships.
        case crash
        /// Logs an error to the console and discards the event.
        case logError
        /// Replaces the event with the error `"davy_invalid_event_name"`, with the rejected
        /// name in its `info` under `"rejected_name"`, so invalid names are reported without
        /// mixing them into your other events. The original event's `info` is not sent.
        case sendError
        /// Silently discards the event.
        case ignore
        /// Sends the event as is, without checking its name.
        case send
    }
}

extension Duration {
    var timeInterval: TimeInterval {
        let (seconds, attoseconds) = components
        return TimeInterval(seconds) + TimeInterval(attoseconds) / 1e18
    }
}
