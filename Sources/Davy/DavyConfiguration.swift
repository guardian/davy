import Foundation

/// Configuration describing where and how ``Davy`` delivers events.
public struct DavyConfiguration: Sendable {
    /// The URL that events are sent to.
    public var endpoint: URL

    /// Allows plain HTTP for development. Leave disabled for production.
    public var allowsInsecureHTTP: Bool

    /// The kind of build sending events, for example `"debug"`, `"beta"`, or `"production"`, sent as
    /// the request's `session.environment`. Omitted when `nil`.
    ///
    /// Use it to separate test data from real usage. TestFlight and App Store builds share a binary,
    /// so telling them apart needs a runtime check or a separate build configuration.
    public var environment: String?

    /// Additional details about the app and session, sent with every event as the request's
    /// `session.info`. Omitted when empty.
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

    /// The largest an event can be, in bytes, measured as the UTF-8 length of a single-event JSON
    /// request body, including the session data and an error's diagnostics, so a single large event
    /// can't make a batch request too large for the server to accept.
    ///
    /// An event over this size is replaced with the error `"davy_event_too_large"`, with its
    /// name in `info` under `"rejected_name"` and its size under `"size"`; the original event's
    /// `info` isn't sent. If the error is also over this size, for example because the name is very
    /// long, nothing is sent.
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
    /// `Content-Type`, `User-Agent`, and `Accept-Language` are always set by Davy. Cookie headers are ignored.
    public var headers: [String: String]

    /// The number of pending events that triggers an immediate send, and the maximum number of
    /// events included in a single request. Values below 1 are treated as 1.
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
    ///   - environment: The kind of build sending events, for example `"debug"`, `"beta"`, or `"production"`.
    ///   - info: Additional details sent with every event in `session.info`.
    ///   - onInvalidEventName: What to do with events whose names aren't lower snake case. Defaults to
    ///     ``InvalidEventNameAction/send``.
    ///   - maxEventSize: The largest an event can be, in bytes, as a single-event JSON request
    ///     body. Defaults to 4,096.
    ///   - logsAppLifecycleEvents: Whether to log `"app_foregrounded"` and `"app_backgrounded"`
    ///     breadcrumbs. Defaults to `false`.
    ///   - headers: Additional HTTP header fields sent with every request.
    ///   - batchSize: The number of pending events that triggers a send. Defaults to 100.
    ///   - debounceInterval: How long to wait after the most recent event before sending. Defaults to 10 seconds.
    ///   - maxEventAge: How long unsent events are kept. Defaults to 30 days.
    ///   - storageDirectory: Where events are persisted. Defaults to ``defaultStorageDirectory``.
    ///   - debugHistoryLimit: How many sent events and requests to keep in memory for debugging. Defaults to 200.
    ///   - allowsInsecureHTTP: Whether to allow plain HTTP for development. Defaults to `false`.
    public init(
        endpoint: URL,
        environment: String? = nil,
        info: [String: String] = [:],
        onInvalidEventName: InvalidEventNameAction = .send,
        maxEventSize: Int = 4096,
        logsAppLifecycleEvents: Bool = false,
        headers: [String: String] = [:],
        batchSize: Int = 100,
        debounceInterval: Duration = .seconds(10),
        maxEventAge: Duration = .seconds(30 * 24 * 60 * 60),
        storageDirectory: URL = DavyConfiguration.defaultStorageDirectory,
        debugHistoryLimit: Int = 200,
        allowsInsecureHTTP: Bool = false
    ) {
        self.endpoint = endpoint
        self.allowsInsecureHTTP = allowsInsecureHTTP
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
