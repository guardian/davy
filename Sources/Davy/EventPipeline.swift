import Foundation
import os

/// Controls how long to wait before sending again after a failure or a server-requested pause.
struct RetryPolicy: Sendable {
    var initialDelay: Duration
    var maxDelay: Duration
    /// The longest `Retry-After` delay honored. Longer values are capped, so a misconfigured
    /// server can't stop delivery indefinitely.
    var maxRetryAfter: Duration = .seconds(24 * 60 * 60)
    /// The fraction of each backoff delay that is randomized, so devices that failed together
    /// don't all retry at the same moment.
    var jitter: Double = 0.5
    /// The most a `Retry-After` delay is randomly lengthened by, as a fraction of the delay.
    /// Retries are never earlier than the server asked.
    var retryAfterJitter: Double = 0.1

    static let `default` = RetryPolicy(initialDelay: .seconds(5), maxDelay: .seconds(300))

    /// Exponential backoff: `initialDelay * 2^(failures - 1)`, capped at `maxDelay`, then
    /// shortened by a random amount of up to `jitter` of the delay.
    func delay(afterFailures failures: Int) -> Duration {
        let exponent = min(max(failures - 1, 0), 16)
        let delay = min(initialDelay * (1 << exponent), maxDelay)
        return delay * (1 - jitter * Double.random(in: 0..<1))
    }

    /// The pause for a server's `Retry-After` value in seconds: at least 1 second, at most
    /// `maxRetryAfter`, then lengthened by a random amount of up to `retryAfterJitter`.
    func pause(forRetryAfter seconds: TimeInterval) -> TimeInterval {
        let pause = min(max(seconds, 1), maxRetryAfter.timeInterval)
        return pause * (1 + retryAfterJitter * Double.random(in: 0..<1))
    }
}

/// Serially processes logged events: persists them, then sends them.
///
/// Sending runs in a child task so new events keep being written to disk while a request is in
/// flight. Only one drain runs at a time; it sends pending events oldest first, one request at a
/// time, until the store is empty, a request fails, or the server asks for a pause.
///
/// - A failure stops sending and schedules a retry with exponential backoff.
/// - A `Retry-After` pause stops all requests until it ends, even when flushing, and is persisted
///   so a relaunch doesn't skip it.
/// - Rejected events are discarded so they don't block the queue.
actor EventPipeline {
    enum Command: Sendable {
        case log(EventRecord)
        /// Merges the dictionary into the current session's `info` and logs the
        /// `"session_updated"` breadcrumb.
        case addSessionInfo([String: Davy.InfoValue], EventRecord)
        /// Sends all pending events, ignoring any retry backoff but not a server-requested pause.
        /// Resumes the continuation once done.
        case flush(CheckedContinuation<Void, Never>?)
        /// Resumes the continuation with the current ``DavyDebugSnapshot``.
        case debugSnapshot(CheckedContinuation<DavyDebugSnapshot, Never>)
        /// Yields a snapshot now and after each change, until removed or the pipeline stops.
        case addDebugObserver(UUID, AsyncStream<DavyDebugSnapshot>.Continuation)
        case removeDebugObserver(UUID)
        /// Forgets the events recorded as sent.
        case clearDebugHistory
    }

    /// Pending events to send in one request, all from the same session.
    private struct Delivery: Sendable {
        let stored: [StoredEvent]
        let session: Session
        let events: [EventRecord]
    }

    private let logger = Logger(subsystem: "Davy", category: "EventPipeline")
    private var session: Session
    private let configuration: DavyConfiguration
    private let batchSize: Int
    private let requestBuilder: RequestBuilder
    private let transport: any EventTransport
    private let retryPolicy: RetryPolicy
    private let pauseFile: URL
    private var store: FileEventStore

    private var debounceTask: Task<Void, Never>?
    /// Waits out a backoff or a server-requested pause, then sends again.
    private var retryTask: Task<Void, Never>?
    private var sendTask: Task<Void, Never>?
    private var consecutiveFailures = 0
    /// When a server-requested pause ends.
    private var pausedUntil: Date?

    /// Events the server responded to, oldest first, up to
    /// ``DavyConfiguration/debugHistoryLimit``.
    private var sentHistory: [DavyDebugSnapshot.SentEvent] = []
    /// Requests that finished, in the order they finished, up to
    /// ``DavyConfiguration/debugHistoryLimit``.
    private var requestHistory: [DavyDebugSnapshot.Request] = []
    /// The most recent failure to send, cleared once the server responds to a request.
    private var lastFailure: DavyDebugSnapshot.Failure?
    /// Events logged and sent each minute, oldest first, back to
    /// ``DavyDebugSnapshot/activityWindow`` ago.
    private var activity: [DavyDebugSnapshot.Activity] = []
    /// Events left over from earlier launches when this instance was created, plus the events it
    /// stored, including any since discarded.
    private var loggedCount: Int
    /// Events the server accepted or rejected since this instance was created.
    private var sentCount = 0
    /// When the first event this instance knows about was logged, for each session.
    private var sessionStarts: [UUID: Date] = [:]
    private var debugObservers: [UUID: AsyncStream<DavyDebugSnapshot>.Continuation] = [:]
    private var isDebugUpdateScheduled = false

    init(
        session: Session,
        configuration: DavyConfiguration,
        transport: any EventTransport,
        retryPolicy: RetryPolicy
    ) {
        self.session = session
        self.configuration = configuration
        self.batchSize = max(1, configuration.batchSize)
        self.requestBuilder = RequestBuilder(configuration: configuration)
        self.transport = transport
        self.retryPolicy = retryPolicy
        self.pauseFile = configuration.storageDirectory.appending(
            path: "paused-until-\(RequestBuilder.destination(for: configuration.endpoint))", directoryHint: .notDirectory
        )
        let store = FileEventStore(directory: configuration.storageDirectory.appending(path: "events", directoryHint: .isDirectory))
        self.store = store
        self.loggedCount = store.count
    }

    /// Consumes commands in order until the stream finishes.
    func run(_ commands: AsyncStream<Command>) async {
        pruneExpiredEvents()
        restorePause()
        recordLeftoverEventsAsLogged()
        if store.count > 0 {
            requestSend(force: false)
        }

        for await command in commands {
            handle(command)
        }

        debounceTask?.cancel()
        retryTask?.cancel()
        for observer in debugObservers.values {
            observer.finish()
        }
        debugObservers.removeAll()
    }

    private func handle(_ command: Command) {
        switch command {
        case let .log(record):
            persist(record)
        case let .addSessionInfo(info, record):
            session = session.addingInfo(info)
            persist(record)
        case let .flush(continuation):
            requestSend(force: true)
            guard let continuation else { return }
            if let sendTask {
                Task {
                    await sendTask.value
                    continuation.resume()
                }
            } else {
                continuation.resume()
            }
        case let .debugSnapshot(continuation):
            continuation.resume(returning: debugSnapshot())
        case let .addDebugObserver(id, observer):
            if case .terminated = observer.yield(debugSnapshot()) { return }
            debugObservers[id] = observer
        case let .removeDebugObserver(id):
            debugObservers[id] = nil
        case .clearDebugHistory:
            sentHistory.removeAll()
            requestHistory.removeAll()
            debugStateDidChange()
        }
    }

    private func persist(_ record: EventRecord) {
        guard let record = recordWithinMaxEventSize(record) else { return }
        do {
            let pending = PendingEvent(session: session, event: record, destination: requestBuilder.destination)
            try store.append(DavyJSON.encoder.encode(pending), loggedAt: record.timestamp)
        } catch {
            logger.error("Failed to store event: \(error)")
            return
        }
        loggedCount += 1
        recordActivity(logged: 1, at: record.timestamp)
        recordSessionEvent(session.id, loggedAt: record.timestamp)
        debugStateDidChange()

        if store.count >= batchSize {
            requestSend(force: false)
        } else {
            scheduleDebouncedSend()
        }
    }

    /// Returns `record` if it fits in ``DavyConfiguration/maxEventSize``, otherwise a
    /// `"davy_event_too_large"` error in its place, or `nil` if even that doesn't fit.
    private func recordWithinMaxEventSize(_ record: EventRecord) -> EventRecord? {
        let maxSize = configuration.maxEventSize
        guard let size = try? requestBuilder.size(of: record, in: session), size > maxSize else {
            return record
        }

        var info: [String: Davy.InfoValue] = [
            Davy.Event.rejectedNameInfoKey: .string(record.name),
            Davy.Event.sizeInfoKey: .int(size),
        ]
        // An error's diagnostics describe when it happened, so they're kept with the replacement.
        if record.type == .error, let recordInfo = record.info {
            info.merge(recordInfo.filter { Diagnostics.keys.contains($0.key) }) { current, _ in current }
        }
        let replacement = EventRecord(
            .error(Davy.Event.tooLargeErrorName, info: info),
            timestamp: record.timestamp
        )
        let replacementSize = (try? requestBuilder.size(of: replacement, in: session)) ?? .max
        guard replacementSize <= maxSize else {
            logger.error("Discarded event \"\(record.name)\": \(size) bytes is over maxEventSize (\(maxSize) bytes)")
            return nil
        }
        logger.error("Replaced event \"\(record.name)\" with \"\(Davy.Event.tooLargeErrorName)\": \(size) bytes is over maxEventSize (\(maxSize) bytes)")
        return replacement
    }

    private func scheduleDebouncedSend() {
        debounceTask?.cancel()
        let interval = configuration.debounceInterval
        debounceTask = Task {
            try? await Task.sleep(for: interval)
            guard !Task.isCancelled else { return }
            self.requestSend(force: false)
        }
    }

    /// Starts draining pending events unless a send is already running or the server asked for a
    /// pause.
    ///
    /// - Parameter force: Skips any retry backoff currently in effect.
    private func requestSend(force: Bool) {
        debounceTask?.cancel()
        debounceTask = nil
        // A server-requested pause is honored even when flushing; it ends by itself.
        guard pausedUntil == nil else { return }
        if force {
            retryTask?.cancel()
            retryTask = nil
        }
        guard retryTask == nil, sendTask == nil else { return }
        sendTask = Task {
            await self.drain()
        }
    }

    private func drain() async {
        defer { sendTask = nil }
        pruneExpiredEvents()

        var failed = false
        while !failed, pausedUntil == nil, let delivery = nextDelivery() {
            let (outcome, request) = await Self.send(delivery, via: transport, requestBuilder: requestBuilder)
            if let request {
                recordRequest(request)
            }

            switch outcome {
            case .delivered:
                store.remove(delivery.stored)
                recordSent(delivery, outcome: .delivered)
                consecutiveFailures = 0
            case let .rejected(statusCode):
                logger.error("Discarding \(delivery.events.count) event(s) rejected with HTTP \(statusCode)")
                store.remove(delivery.stored)
                recordSent(delivery, outcome: .rejected(statusCode: statusCode))
            case let .throttled(retryAfter):
                logger.info("Server asked to retry after \(retryAfter) seconds")
                pause(forRetryAfter: retryAfter)
                recordFailure("Server asked to retry after \(Int(retryAfter.rounded(.up))) seconds")
            case let .failed(reason):
                logger.error("Failed to send \(delivery.events.count) event(s): \(reason)")
                failed = true
                recordFailure(reason)
            }
        }

        if pausedUntil != nil {
            schedulePauseEnd()
        } else if failed {
            scheduleRetry()
        }
    }

    /// The oldest pending events that can go in one request: up to `batchSize` events from the
    /// same session.
    private func nextDelivery() -> Delivery? {
        while true {
            let batch = store.oldest(batchSize)
            guard !batch.isEmpty else { return nil }
            let decoded = decode(batch)
            guard let session = decoded.first?.pending.session else { continue }

            // Each request carries a single session, so take the leading run of events with the
            // same session; any later sessions (or later `session.info` values) go in the next
            // request.
            let run = decoded.prefix { $0.pending.session == session }
            return Delivery(stored: run.map(\.stored), session: session, events: run.map(\.pending.event))
        }
    }

    /// Sends `delivery`, and describes the request for the debug history unless it couldn't be
    /// built.
    private static func send(
        _ delivery: Delivery,
        via transport: any EventTransport,
        requestBuilder: RequestBuilder
    ) async -> (outcome: DeliveryOutcome, request: DavyDebugSnapshot.Request?) {
        let request: URLRequest
        let sentAt = Date.now
        do {
            request = try requestBuilder.request(session: delivery.session, events: delivery.events, sentAt: sentAt)
        } catch {
            return (.failed("Couldn't build request: \(error)"), nil)
        }
        let clock = ContinuousClock()
        let start = clock.now
        let (outcome, statusCode) = await transport.deliver(request)
        let sent = DavyDebugSnapshot.Request(
            id: UUID(),
            headers: request.allHTTPHeaderFields ?? [:],
            body: request.httpBody.map { String(decoding: $0, as: UTF8.self) } ?? "",
            eventIDs: delivery.events.map(\.id),
            sentAt: sentAt,
            duration: (clock.now - start).timeInterval,
            statusCode: statusCode,
            outcome: DavyDebugSnapshot.Request.Outcome(outcome)
        )
        return (outcome, sent)
    }

    /// Discards invalid records and records not bound to the configured endpoint.
    private func decode(_ batch: [StoredEvent]) -> [(stored: StoredEvent, pending: PendingEvent)] {
        var decoded: [(stored: StoredEvent, pending: PendingEvent)] = []
        var undecodable: [StoredEvent] = []
        var otherDestination: [StoredEvent] = []
        for stored in batch {
            if let pending = try? DavyJSON.decoder.decode(PendingEvent.self, from: stored.data) {
                if pending.destination == requestBuilder.destination {
                    decoded.append((stored, pending))
                } else {
                    otherDestination.append(stored)
                }
            } else {
                undecodable.append(stored)
            }
        }
        if !undecodable.isEmpty {
            logger.error("Discarding \(undecodable.count) undecodable event(s)")
            store.discard(undecodable)
        }
        if !otherDestination.isEmpty {
            logger.info("Discarding \(otherDestination.count) event(s) without a matching destination")
            store.discard(otherDestination)
        }
        return decoded
    }

    private func scheduleRetry() {
        consecutiveFailures += 1
        let delay = retryPolicy.delay(afterFailures: consecutiveFailures)
        retryTask?.cancel()
        retryTask = Task {
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled else { return }
            self.retryTask = nil
            self.requestSend(force: false)
        }
    }

    /// Pauses sending for a server's `Retry-After` value, unless a longer pause is already set.
    private func pause(forRetryAfter seconds: TimeInterval) {
        let until = Date.now.addingTimeInterval(retryPolicy.pause(forRetryAfter: seconds))
        if let pausedUntil, pausedUntil >= until { return }
        pausedUntil = until
        do {
            try Data(String(until.timeIntervalSince1970).utf8).write(to: pauseFile, options: .atomic)
        } catch {
            logger.error("Failed to store pause: \(error)")
        }
    }

    /// Resumes a pause stored by a previous launch, if it hasn't ended yet.
    private func restorePause() {
        let stored = (try? String(contentsOf: pauseFile, encoding: .utf8))
            .flatMap { TimeInterval($0) }
            .map { Date(timeIntervalSince1970: $0) }
        guard let stored, stored > .now else {
            try? FileManager.default.removeItem(at: pauseFile)
            return
        }
        // Capped in case the device clock moved backwards since the pause was stored.
        pausedUntil = min(stored, .now.addingTimeInterval(retryPolicy.maxRetryAfter.timeInterval))
        schedulePauseEnd()
    }

    private func schedulePauseEnd() {
        guard let pausedUntil else { return }
        let delay = Duration.seconds(max(0, pausedUntil.timeIntervalSinceNow))
        retryTask?.cancel()
        retryTask = Task {
            try? await Task.sleep(for: delay)
            guard !Task.isCancelled else { return }
            self.endPause()
        }
    }

    private func endPause() {
        pausedUntil = nil
        retryTask = nil
        try? FileManager.default.removeItem(at: pauseFile)
        requestSend(force: false)
    }

    private func pruneExpiredEvents() {
        let count = store.count
        store.prune(olderThan: Date.now.addingTimeInterval(-configuration.maxEventAge.timeInterval))
        if store.count != count {
            debugStateDidChange()
        }
    }

    private func recordSent(_ delivery: Delivery, outcome: DavyDebugSnapshot.SentEvent.Outcome) {
        lastFailure = nil
        let sentAt = Date.now
        sentCount += delivery.events.count
        recordActivity(sent: delivery.events.count, at: sentAt)
        for record in delivery.events {
            recordSessionEvent(delivery.session.id, loggedAt: record.timestamp)
        }
        let limit = configuration.debugHistoryLimit
        if limit > 0 {
            sentHistory.append(contentsOf: delivery.events.suffix(limit).map { record in
                DavyDebugSnapshot.SentEvent(
                    event: DavyDebugSnapshot.LoggedEvent(record: record, session: delivery.session),
                    sentAt: sentAt,
                    outcome: outcome
                )
            })
            if sentHistory.count > limit {
                sentHistory.removeFirst(sentHistory.count - limit)
            }
        }
        debugStateDidChange()
    }

    private func recordFailure(_ reason: String) {
        lastFailure = DavyDebugSnapshot.Failure(reason: reason, date: .now)
        debugStateDidChange()
    }

    private func recordRequest(_ request: DavyDebugSnapshot.Request) {
        let limit = configuration.debugHistoryLimit
        guard limit > 0 else { return }
        requestHistory.append(request)
        if requestHistory.count > limit {
            requestHistory.removeFirst(requestHistory.count - limit)
        }
        debugStateDidChange()
    }

    /// Counts events left over from earlier launches as logged when they were, so activity
    /// includes every event this instance knows about. Called before any of this instance's
    /// events are stored, so none are counted twice.
    private func recordLeftoverEventsAsLogged() {
        let start = DavyDebugSnapshot.activityStart(at: .now)
        for date in store.loggedDates where date >= start {
            recordActivity(logged: 1, at: date)
        }
    }

    /// Adds to the counts for the minute containing `date`, and forgets minutes that are now
    /// over an hour old.
    private func recordActivity(logged: Int = 0, sent: Int = 0, at date: Date) {
        let minute = DavyDebugSnapshot.Activity.minute(containing: date)
        if let index = activity.firstIndex(where: { $0.minute == minute }) {
            activity[index].logged += logged
            activity[index].sent += sent
        } else {
            // Usually the newest minute, unless the clock moved backwards.
            let index = activity.firstIndex { $0.minute > minute } ?? activity.endIndex
            activity.insert(DavyDebugSnapshot.Activity(minute: minute, logged: logged, sent: sent), at: index)
        }
        let start = DavyDebugSnapshot.activityStart(at: .now)
        activity.removeAll { $0.minute < start }
    }

    /// Moves the session's start earlier if this is the earliest of its events seen so far.
    ///
    /// Events left over from earlier launches are seen as they're sent, or read back from disk for
    /// a snapshot, so their sessions are known by the time a snapshot needs them.
    private func recordSessionEvent(_ sessionID: UUID, loggedAt date: Date) {
        if let start = sessionStarts[sessionID], start <= date { return }
        sessionStarts[sessionID] = date
    }

    /// Every pending event read back from disk, discarding any that can't be decoded so the
    /// counts add up, with the sent and request histories, last failure, counts, activity, and
    /// session starts.
    private func debugSnapshot() -> DavyDebugSnapshot {
        let pending = decode(store.oldest(store.count)).map {
            DavyDebugSnapshot.LoggedEvent(record: $0.pending.event, session: $0.pending.session)
        }
        for event in pending {
            recordSessionEvent(event.sessionID, loggedAt: event.timestamp)
        }
        let activityStart = DavyDebugSnapshot.activityStart(at: .now)
        return DavyDebugSnapshot(
            pending: pending,
            sent: sentHistory,
            requests: requestHistory,
            loggedCount: loggedCount - store.discardedCount,
            sentCount: sentCount,
            lastFailure: lastFailure,
            activity: activity.filter { $0.minute >= activityStart },
            sessionStarts: sessionStarts
                .filter { $0.value >= activityStart }
                .map { DavyDebugSnapshot.SessionStart(sessionID: $0.key, date: $0.value) }
                .sorted { $0.date < $1.date }
        )
    }

    /// Sends observers a new snapshot once the current work is done, so a burst of changes, such
    /// as many events logged at once, reads pending events from disk only once.
    private func debugStateDidChange() {
        guard !debugObservers.isEmpty, !isDebugUpdateScheduled else { return }
        isDebugUpdateScheduled = true
        Task {
            self.isDebugUpdateScheduled = false
            guard !self.debugObservers.isEmpty else { return }
            let snapshot = self.debugSnapshot()
            for (id, observer) in self.debugObservers {
                if case .terminated = observer.yield(snapshot) {
                    self.debugObservers[id] = nil
                }
            }
        }
    }
}
