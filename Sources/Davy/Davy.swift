import Foundation
import os

/// Logs breadcrumbs and errors, persists them to disk, and sends them to your endpoint.
///
/// ```swift
/// let telemetry = Davy(configuration: .init(
///     endpoint: URL(string: "https://api.example.com/events")!
/// ))
/// telemetry.log(.breadcrumb("checkout_started"))
/// telemetry.log(.error("payment_declined"))
/// ```
///
/// `log(_:)` and `flush()` are synchronous and can be called from any thread. Events are processed
/// in the order they are logged. By default each request body is a JSON object containing events
/// from a single session, along with common app and device data captured when that session
/// started:
///
/// ```json
/// {
///   "session": { "id": "…", "environment": "production", "appVersion": "1.2.0", "build": "45", "os": "iOS", "osVersion": "26.1.0", "deviceModel": "iPhone17,1", "locale": "en_GB" },
///   "events": [ { "id": "…", "type": "breadcrumb", "name": "checkout_started", "timestamp": "…" } ]
/// }
/// ```
///
/// With ``DavyConfiguration/RequestFormat/jsonEvent(maxConcurrentRequests:)``, each event is
/// sent as its own request with this body, containing only that event. With
/// ``DavyConfiguration/RequestFormat/queryString(method:maxConcurrentRequests:)``, each
/// event is sent as its own request with the same fields in the URL instead.
///
/// Events are sent when ``DavyConfiguration/batchSize`` events are pending, when
/// ``DavyConfiguration/debounceInterval`` passes without a new event, when the app moves
/// to the background, and on the next launch for anything left over. Events are only removed
/// after the server responds with a 2xx status, or with a status meaning they will never be
/// accepted, such as 400; otherwise they are retried later with exponential backoff. A 429 or 503
/// response with a `Retry-After` header pauses all sending for that long. Events older than
/// ``DavyConfiguration/maxEventAge`` are discarded.
public final class Davy: Sendable {
    /// A random identifier sent with this instance's events, as the request's `session.id` (or
    /// `sessionId` query parameter).
    ///
    /// Generated each time an instance is created (typically once per app launch) and never
    /// reused, so it links events within a session without identifying the user or device across
    /// sessions.
    public let sessionID: UUID

    /// The configuration this instance was created with.
    public let configuration: DavyConfiguration

    private let commands: AsyncStream<EventPipeline.Command>.Continuation
    private let lifecycleTask: Task<Void, Never>?
    private let logger = Logger(subsystem: "Davy", category: "Davy")

    /// Creates an instance with a new session and starts sending any events left over from a
    /// previous session.
    public convenience init(configuration: DavyConfiguration) {
        self.init(configuration: configuration, transport: URLSessionTransport())
    }

    init(
        configuration: DavyConfiguration,
        transport: any EventTransport,
        retryPolicy: RetryPolicy = .default,
        notificationCenter: NotificationCenter? = .default
    ) {
        self.configuration = configuration
        let session = Session.start(environment: configuration.environment)
        self.sessionID = session.id

        let (stream, commands) = AsyncStream.makeStream(of: EventPipeline.Command.self)
        self.commands = commands

        let pipeline = EventPipeline(
            session: session,
            configuration: configuration,
            transport: transport,
            retryPolicy: retryPolicy
        )
        Task {
            await pipeline.run(stream)
        }

        let logsAppLifecycleEvents = configuration.logsAppLifecycleEvents
        lifecycleTask = notificationCenter.flatMap { center in
            AppLifecycle.observe(
                center: center,
                didEnterForeground: {
                    guard logsAppLifecycleEvents else { return }
                    commands.yield(.log(EventRecord(.appForegrounded, timestamp: Date.now)))
                },
                didEnterBackground: {
                    guard logsAppLifecycleEvents else { return }
                    commands.yield(.log(EventRecord(.appBackgrounded, timestamp: Date.now)))
                },
                flush: { await Self.flush(commands) }
            )
        }
    }

    deinit {
        lifecycleTask?.cancel()
        commands.finish()
    }

    /// Records a breadcrumb or error. Returns immediately; the event is persisted and sent in the
    /// background with this instance's session data and the current time.
    ///
    /// Errors also have the app's CPU and memory usage, the memory it has left, the device's
    /// thermal state, and whether Low Power Mode is on added to `info`, captured during this call.
    /// See ``Event/error(_:info:)`` for the keys.
    ///
    /// Names that aren't lower snake case are handled according to
    /// ``DavyConfiguration/onInvalidEventName``, and events over
    /// ``DavyConfiguration/maxEventSize`` are replaced with an error.
    public func log(_ event: Event) {
        guard var event = eventToLog(event) else { return }
        let timestamp = Date.now
        if case let .error(name, info) = event {
            event = .error(name, info: info.merging(Diagnostics.capture()) { _, captured in captured })
        }
        commands.yield(.log(EventRecord(event, timestamp: timestamp)))
    }

    /// Records an error named `name`, with the type of `error` in `info` under `error_type`.
    ///
    /// The error's description isn't sent, since it can contain personal data. Any `error_type`
    /// already in `info` is replaced.
    public func log(_ error: any Error, name: String, info: [String: String] = [:]) {
        var info = info
        info["error_type"] = String(describing: type(of: error))
        log(.error(name, info: info))
    }

    /// Times `body` and logs a breadcrumb named `name` with the duration in milliseconds under
    /// `duration_ms` in `info`. The breadcrumb is logged even if `body` throws.
    public func measure<T>(_ name: String, _ body: () throws -> T) rethrows -> T {
        let start = ContinuousClock.now
        defer { logDuration(name, since: start) }
        return try body()
    }

    /// Times an asynchronous `body` and logs a breadcrumb named `name` with the duration in
    /// milliseconds under `duration_ms` in `info`. The breadcrumb is logged even if `body` throws.
    public func measure<T>(_ name: String, _ body: () async throws -> T) async rethrows -> T {
        let start = ContinuousClock.now
        defer { logDuration(name, since: start) }
        return try await body()
    }

    private func logDuration(_ name: String, since start: ContinuousClock.Instant) {
        let milliseconds = (ContinuousClock.now - start).timeInterval * 1000
        log(.breadcrumb(name, info: ["duration_ms": String(Int(milliseconds.rounded()))]))
    }

    /// Applies ``DavyConfiguration/onInvalidEventName`` to `event`, returning the event to
    /// log, if any.
    private func eventToLog(_ event: Event) -> Event? {
        let action = configuration.onInvalidEventName
        if action == .send || Event.isLowerSnakeCase(event.name) {
            return event
        }
        switch action {
        case .crash:
            fatalError("Davy event name \"\(event.name)\" isn't lower snake case, for example \"checkout_started\".")
        case .logError:
            logger.error("Discarded event \"\(event.name)\": name isn't lower snake case, for example \"checkout_started\".")
            return nil
        case .sendError:
            return .error(Event.invalidNameErrorName, info: [Event.rejectedNameInfoKey: event.name])
        case .ignore:
            return nil
        case .send:
            return event
        }
    }

    /// Sends all pending events now, bypassing the debounce interval and any retry backoff, but not
    /// a pause the server asked for with `Retry-After`.
    public func flush() {
        commands.yield(.flush(nil))
    }

    /// Sends all pending events now, like ``flush()``, and waits for the attempt to finish.
    ///
    /// Use this where the process can be suspended as soon as you're done, such as an app
    /// extension, which isn't flushed automatically:
    ///
    /// ```swift
    /// telemetry.log(.breadcrumb("push_received", info: ["push_id": pushID]))
    /// await telemetry.flushAndWait()
    /// contentHandler(content)
    /// ```
    ///
    /// Returns once the server has responded to every pending event, or sending stops because of
    /// an error or a pause the server asked for with `Retry-After`. Events that weren't delivered
    /// stay on disk and are sent later.
    public func flushAndWait() async {
        await Self.flush(commands)
    }

    private static func flush(_ commands: AsyncStream<EventPipeline.Command>.Continuation) async {
        await withCheckedContinuation { continuation in
            if case .terminated = commands.yield(.flush(continuation)) {
                continuation.resume()
            }
        }
    }

    /// Returns the events waiting to be sent, the events recently sent, and the most recent
    /// failure to send, for checking your events during development. ``DavyDebugView`` shows
    /// the same information.
    ///
    /// Includes every event logged before this call.
    public func debugSnapshot() async -> DavyDebugSnapshot {
        await withCheckedContinuation { continuation in
            if case .terminated = commands.yield(.debugSnapshot(continuation)) {
                continuation.resume(returning: .empty)
            }
        }
    }

    /// Returns a stream of ``DavyDebugSnapshot``s: one right away, then another each time
    /// events are logged, sent, or fail to send, until you stop iterating.
    ///
    /// Changes made close together produce a single snapshot, and if you're slow to iterate you
    /// only get the latest one.
    ///
    /// ```swift
    /// for await snapshot in telemetry.debugUpdates() {
    ///     print("\(snapshot.pending.count) pending, \(snapshot.sent.count) sent")
    /// }
    /// ```
    public func debugUpdates() -> AsyncStream<DavyDebugSnapshot> {
        let (updates, continuation) = AsyncStream.makeStream(
            of: DavyDebugSnapshot.self,
            bufferingPolicy: .bufferingNewest(1)
        )
        let id = UUID()
        continuation.onTermination = { [commands] _ in
            commands.yield(.removeDebugObserver(id))
        }
        if case .terminated = commands.yield(.addDebugObserver(id, continuation)) {
            continuation.finish()
        }
        return updates
    }

    /// Forgets the events recorded as sent and the requests recorded, so
    /// ``DavyDebugSnapshot/sent`` and ``DavyDebugSnapshot/requests`` start empty again. Pending
    /// events aren't affected.
    public func clearDebugHistory() {
        commands.yield(.clearDebugHistory)
    }
}
