import Foundation
import Testing
@testable import Davy

private struct ReceivedPayload: Decodable {
    let session: ReceivedSession
    let events: [ReceivedEvent]
}

private struct ReceivedSession: Decodable {
    let id: UUID
    let environment: String?
    let appVersion: String?
    let build: String?
    let os: String
    let osVersion: String
    let deviceModel: String?
    let locale: String?
    let info: [String: String]?
}

private struct ReceivedEvent: Decodable {
    let id: UUID
    let type: String
    let name: String
    let info: [String: String]?
    let timestamp: String

    /// `info` without the diagnostics added to errors.
    var loggedInfo: [String: String]? { info?.filter { !Diagnostics.keys.contains($0.key) } }
}

/// Records requests and answers each with the current status code and headers, after `delay`.
private actor MockTransport: EventTransport {
    /// Every request, in the order they started.
    private(set) var requests: [URLRequest] = []
    /// Requests answered with a 2xx status.
    private(set) var delivered: [URLRequest] = []
    /// Decoded JSON bodies of requests answered with a 2xx status.
    private(set) var payloads: [ReceivedPayload] = []
    private var statusCode = 200
    private var headers: [String: String] = [:]
    private var delay: Duration = .zero
    private var isOffline = false

    var attempts: Int { requests.count }
    var sentEvents: [ReceivedEvent] { payloads.flatMap(\.events) }
    var sentNames: [String] { sentEvents.map(\.name) }

    func setFailing(_ failing: Bool) {
        respond(with: failing ? 500 : 200)
    }

    func respond(with statusCode: Int, headers: [String: String] = [:]) {
        self.statusCode = statusCode
        self.headers = headers
    }

    func setDelay(_ delay: Duration) {
        self.delay = delay
    }

    /// Fails every request without a response, like a device with no connection.
    func setOffline(_ offline: Bool) {
        isOffline = offline
    }

    func send(_ request: URLRequest) async throws -> HTTPURLResponse {
        requests.append(request)
        let (statusCode, headers) = (statusCode, headers)
        if delay > .zero {
            try await Task.sleep(for: delay)
        }
        if isOffline {
            throw URLError(.notConnectedToInternet)
        }

        if (200..<300).contains(statusCode) {
            delivered.append(request)
            if let body = request.httpBody {
                payloads.append(try JSONDecoder().decode(ReceivedPayload.self, from: body))
            }
        }
        let url = try #require(request.url)
        return try #require(HTTPURLResponse(url: url, statusCode: statusCode, httpVersion: nil, headerFields: headers))
    }
}

private func makeTemporaryDirectory() -> URL {
    FileManager.default.temporaryDirectory
        .appending(path: "DavyTests-\(UUID().uuidString)", directoryHint: .isDirectory)
}

private func makeDavy(
    directory: URL,
    transport: MockTransport,
    endpoint: String = "https://example.com/events",
    environment: String? = nil,
    info: [String: String] = [:],
    onInvalidEventName: DavyConfiguration.InvalidEventNameAction = .send,
    maxEventSize: Int = 4096,
    logsAppLifecycleEvents: Bool = false,
    headers: [String: String] = [:],
    batchSize: Int = 100,
    debounceInterval: Duration = .seconds(60),
    debugHistoryLimit: Int = 200,
    notificationCenter: NotificationCenter? = nil
) -> Davy {
    Davy(
        configuration: DavyConfiguration(
            endpoint: URL(string: endpoint)!,
            environment: environment,
            info: info,
            onInvalidEventName: onInvalidEventName,
            maxEventSize: maxEventSize,
            logsAppLifecycleEvents: logsAppLifecycleEvents,
            headers: headers,
            batchSize: batchSize,
            debounceInterval: debounceInterval,
            storageDirectory: directory,
            debugHistoryLimit: debugHistoryLimit
        ),
        transport: transport,
        retryPolicy: RetryPolicy(initialDelay: .seconds(60), maxDelay: .seconds(60)),
        notificationCenter: notificationCenter
    )
}

/// Polls `condition` until it holds or `timeout` elapses.
private func waitUntil(timeout: Duration = .seconds(5), _ condition: () async -> Bool) async -> Bool {
    let deadline = ContinuousClock.now + timeout
    while ContinuousClock.now < deadline {
        if await condition() {
            return true
        }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return await condition()
}

struct DavyTests {
    @Test func breadcrumbsAndErrorsAreSentInOrderWithSessionData() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        let telemetry = makeDavy(
            directory: directory,
            transport: transport,
            environment: "beta",
            info: ["edition": "uk", "tier": "supporter"]
        )

        telemetry.log(.breadcrumb("checkout_started"))
        telemetry.log(.error("payment_declined", info: ["reason": "card_expired"]))
        telemetry.log(.breadcrumb("checkout_cancelled"))
        await telemetry.flushAndWait()

        let payloads = await transport.payloads
        #expect(payloads.count == 1)
        let payload = try #require(payloads.first)
        #expect(payload.events.map(\.type) == ["breadcrumb", "error", "breadcrumb"])
        #expect(payload.events.map(\.name) == ["checkout_started", "payment_declined", "checkout_cancelled"])
        #expect(payload.events.map(\.loggedInfo) == [nil, ["reason": "card_expired"], nil])
        #expect(Set(payload.events.map(\.id)).count == 3)
        for event in payload.events {
            let timestamp = try Date(event.timestamp, strategy: Date.ISO8601FormatStyle(includingFractionalSeconds: true))
            #expect(abs(timestamp.timeIntervalSinceNow) < 60)
        }

        let expected = Session.start(environment: "beta", info: ["edition": "uk", "tier": "supporter"])
        let session = payload.session
        #expect(session.id == telemetry.sessionID)
        #expect(session.environment == "beta")
        #expect(session.appVersion == expected.appVersion)
        #expect(session.build == expected.build)
        #expect(session.os == expected.os)
        #expect(session.osVersion == expected.osVersion)
        #expect(session.deviceModel == expected.deviceModel)
        #expect(session.deviceModel?.isEmpty == false)
        #expect(session.locale == expected.locale)
        #expect(session.info == ["edition": "uk", "tier": "supporter"])
    }

    @Test func addSessionInfoReplacesMatchingKeysAndLogsASessionUpdatedBreadcrumb() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        let telemetry = makeDavy(directory: directory, transport: transport, info: ["edition": "uk", "theme": "dark"])

        telemetry.log(.breadcrumb("app_launched"))
        telemetry.addSessionInfo(["edition": "au", "tier": "supporter"])
        telemetry.log(.breadcrumb("article_opened"))
        await telemetry.flushAndWait()

        let payloads = await transport.payloads
        #expect(payloads.count == 2)
        #expect(payloads.map(\.session.id) == [telemetry.sessionID, telemetry.sessionID])
        #expect(payloads[0].session.info == ["edition": "uk", "theme": "dark"])
        #expect(payloads[0].events.map(\.name) == ["app_launched"])

        #expect(payloads[1].session.info == ["edition": "au", "theme": "dark", "tier": "supporter"])
        #expect(payloads[1].events.map(\.name) == ["session_updated", "article_opened"])
        #expect(payloads[1].events.map(\.type) == ["breadcrumb", "breadcrumb"])
        #expect(payloads[1].events[0].loggedInfo == ["edition": "au", "tier": "supporter"])
    }

    @Test func errorsHaveDiagnosticsAddedToInfoButBreadcrumbsDoNot() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        let telemetry = makeDavy(directory: directory, transport: transport, onInvalidEventName: .sendError)

        telemetry.log(.breadcrumb("checkout_started"))
        // Diagnostics replace any values already in `info` under the same keys.
        telemetry.log(.error("payment_declined", info: ["reason": "card_expired", "low_power_mode": "unknown"]))
        // Errors that replace an invalid name are captured when logged too.
        telemetry.log(.breadcrumb("checkoutStarted"))
        await telemetry.flushAndWait()

        let events = await transport.sentEvents
        #expect(events.map(\.name) == ["checkout_started", "payment_declined", "davy_invalid_event_name"])
        #expect(events.map(\.loggedInfo) == [nil, ["reason": "card_expired"], ["rejected_name": "checkoutStarted"]])
        for event in events.dropFirst() {
            let info = try #require(event.info)
            #expect(try #require(info["cpu_percent"].flatMap(Int.init)) >= 0)
            #expect(try #require(info["memory_used_mb"].flatMap(Int.init)) > 0)
            #expect(["nominal", "fair", "serious", "critical"].contains(try #require(info["thermal_state"])))
            #expect(info["low_power_mode"] == String(ProcessInfo.processInfo.isLowPowerModeEnabled))
            #if os(macOS)
            #expect(info["memory_available_mb"] == nil)
            #endif
        }
    }

    @Test func unsentEventsSurviveRelaunchAndAreSentWithTheirOriginalSession() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }

        let failingTransport = MockTransport()
        await failingTransport.setFailing(true)
        var firstSessionID: UUID?
        do {
            let telemetry = makeDavy(directory: directory, transport: failingTransport)
            firstSessionID = telemetry.sessionID
            telemetry.log(.breadcrumb("event-0"))
            telemetry.log(.error("event-1"))
            await telemetry.flushAndWait()
        }
        #expect(await failingTransport.attempts == 1)

        let transport = MockTransport()
        let relaunched = makeDavy(directory: directory, transport: transport)
        relaunched.log(.breadcrumb("event-2"))
        await relaunched.flushAndWait()

        let payloads = await transport.payloads
        #expect(relaunched.sessionID != firstSessionID)
        #expect(payloads.map(\.session.id) == [firstSessionID, relaunched.sessionID])
        #expect(payloads.map { $0.events.map(\.name) } == [["event-0", "event-1"], ["event-2"]])
        #expect(payloads.map { $0.events.map(\.type) } == [["breadcrumb", "error"], ["breadcrumb"]])
    }

    @Test func reachingBatchSizeSendsWithoutWaitingForDebounce() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        let telemetry = makeDavy(directory: directory, transport: transport, batchSize: 3)

        for index in 0..<3 {
            telemetry.log(.breadcrumb("event-\(index)"))
        }

        #expect(await waitUntil { await transport.sentEvents.count == 3 })
        #expect(await transport.payloads.count == 1)
    }

    @Test func debounceSendsPartialBatchAfterQuietPeriod() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        let telemetry = makeDavy(directory: directory, transport: transport, debounceInterval: .milliseconds(100))

        telemetry.log(.breadcrumb("event-0"))
        telemetry.log(.breadcrumb("event-1"))

        #expect(await waitUntil { await transport.sentEvents.count == 2 })
        #expect(await transport.payloads.count == 1)
    }

    @Test(arguments: [true, false])
    func backgroundingFlushesAndLogsTransitionsWhenConfiguredTo(logsAppLifecycleEvents: Bool) async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        let center = NotificationCenter()
        let foreground = try #require(AppLifecycle.foregroundNotification)
        let background = try #require(AppLifecycle.backgroundNotification)
        let telemetry = makeDavy(
            directory: directory,
            transport: transport,
            logsAppLifecycleEvents: logsAppLifecycleEvents,
            notificationCenter: center
        )

        // Like launching, entering the foreground before ever leaving it isn't logged.
        center.post(name: foreground, object: nil)
        telemetry.log(.breadcrumb("checkout_started"))
        center.post(name: background, object: nil)
        // The debounce interval is long, so only backgrounding sends these.
        let firstFlush = ["checkout_started"] + (logsAppLifecycleEvents ? ["app_backgrounded"] : [])
        #expect(await waitUntil { await transport.sentNames == firstFlush })

        telemetry.log(.breadcrumb("checkout_finished"))
        center.post(name: foreground, object: nil)
        center.post(name: background, object: nil)
        let secondFlush = ["checkout_finished"] + (logsAppLifecycleEvents ? ["app_foregrounded", "app_backgrounded"] : [])
        #expect(await waitUntil { await transport.sentNames == firstFlush + secondFlush })
    }

    @Test func failedEventsAreKeptAndSentInBatchSizedChunks() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        await transport.setFailing(true)
        let telemetry = makeDavy(directory: directory, transport: transport, batchSize: 2)

        for index in 0..<5 {
            telemetry.log(.breadcrumb("event-\(index)"))
        }
        await telemetry.flushAndWait()
        #expect(await transport.payloads.isEmpty)

        await transport.setFailing(false)
        await telemetry.flushAndWait()

        let payloads = await transport.payloads
        #expect(payloads.map(\.events.count) == [2, 2, 1])
        #expect(await transport.sentNames == (0..<5).map { "event-\($0)" })
    }

    @Test func eventsOlderThanMaxEventAgeAreDiscarded() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let eventsDirectory = directory.appending(path: "events", directoryHint: .isDirectory)
        var store = FileEventStore(directory: eventsDirectory)
        let day: TimeInterval = 24 * 60 * 60
        let session = Session.start(environment: nil)
        for (name, loggedAt) in [("expired", Date.now.addingTimeInterval(-31 * day)), ("recent", Date.now.addingTimeInterval(-29 * day))] {
            let pending = PendingEvent(
                session: session, event: EventRecord(.breadcrumb(name), timestamp: loggedAt),
                destination: RequestBuilder.destination(for: URL(string: "https://example.com/events")!)
            )
            try store.append(DavyJSON.encoder.encode(pending), loggedAt: loggedAt)
        }

        let transport = MockTransport()
        let telemetry = makeDavy(directory: directory, transport: transport)
        await telemetry.flushAndWait()

        #expect(await transport.sentNames == ["recent"])
        #expect(FileEventStore(directory: eventsDirectory).count == 0)
    }

    @Test func `Only events bound to the current endpoint are sent`() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let eventsDirectory = directory.appending(path: "events", directoryHint: .isDirectory)
        var store = FileEventStore(directory: eventsDirectory)
        let session = Session.start(environment: nil)
        let oldEndpoint = try #require(URL(string: "https://example.com/old"))
        let timestamp = Date.now.addingTimeInterval(-60)
        for (name, destination) in [("legacy", nil), ("old", RequestBuilder.destination(for: oldEndpoint))] {
            let record = PendingEvent(
                session: session, event: EventRecord(.breadcrumb(name), timestamp: timestamp), destination: destination
            )
            try store.append(DavyJSON.encoder.encode(record), loggedAt: timestamp)
        }

        let transport = MockTransport()
        let telemetry = makeDavy(directory: directory, transport: transport, batchSize: 1)
        telemetry.log(.breadcrumb("current"))
        await telemetry.flushAndWait()

        #expect(await transport.sentNames == ["current"])
        #expect(FileEventStore(directory: eventsDirectory).count == 0)
    }

    @Test func undecodableStoredEventsAreDiscardedWithoutBlockingTheQueue() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let eventsDirectory = directory.appending(path: "events", directoryHint: .isDirectory)
        var store = FileEventStore(directory: eventsDirectory)
        try store.append(Data(#"{"not":"an event"}"#.utf8), loggedAt: .now.addingTimeInterval(-60))

        let transport = MockTransport()
        let telemetry = makeDavy(directory: directory, transport: transport)
        telemetry.log(.breadcrumb("event-0"))
        await telemetry.flushAndWait()

        #expect(await transport.sentNames == ["event-0"])
        #expect(FileEventStore(directory: eventsDirectory).count == 0)
    }

    @Test func logIsSafeToCallFromManyThreads() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        let telemetry = makeDavy(directory: directory, transport: transport)

        DispatchQueue.concurrentPerform(iterations: 250) { index in
            telemetry.log(.breadcrumb("event-\(index)"))
        }
        await telemetry.flushAndWait()

        let names = await transport.sentNames
        #expect(names.count == 250)
        #expect(Set(names) == Set((0..<250).map { "event-\($0)" }))
    }

    @Test func eventNamesMustBeLowerSnakeCase() {
        for name in ["checkout_started", "a", "step_2", "http_404_received"] {
            #expect(Davy.Event.isLowerSnakeCase(name), "\(name)")
        }
        let invalidNames = [
            "", "checkoutStarted", "Checkout_started", "CHECKOUT_STARTED", "checkout-started", "checkout started",
            "checkout__started", "_checkout", "checkout_", "2fa_enabled", "café_opened",
        ]
        for name in invalidNames {
            #expect(!Davy.Event.isLowerSnakeCase(name), "\(name)")
        }
    }

    @Test(arguments: [
        (DavyConfiguration.InvalidEventNameAction.send, ["checkout_started", "checkoutStarted", "payment-declined"]),
        (.logError, ["checkout_started"]),
        (.ignore, ["checkout_started"]),
    ])
    func invalidEventNamesAreHandledAsConfigured(
        action: DavyConfiguration.InvalidEventNameAction,
        expectedNames: [String]
    ) async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        let telemetry = makeDavy(directory: directory, transport: transport, onInvalidEventName: action)

        telemetry.log(.breadcrumb("checkout_started"))
        telemetry.log(.breadcrumb("checkoutStarted"))
        telemetry.log(.error("payment-declined"))
        await telemetry.flushAndWait()

        #expect(await transport.sentNames == expectedNames)
    }

    @Test func invalidEventNamesAreReplacedWithAnErrorWhenConfiguredTo() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        let telemetry = makeDavy(directory: directory, transport: transport, onInvalidEventName: .sendError)

        telemetry.log(.breadcrumb("checkout_started", info: ["step": "cart"]))
        telemetry.log(.breadcrumb("checkoutStarted", info: ["step": "cart"]))
        await telemetry.flushAndWait()

        let events = await transport.sentEvents
        #expect(events.map(\.type) == ["breadcrumb", "error"])
        #expect(events.map(\.name) == ["checkout_started", "davy_invalid_event_name"])
        #expect(events.map(\.loggedInfo) == [["step": "cart"], ["rejected_name": "checkoutStarted"]])
    }

    @Test func eventsOverMaxEventSizeAreReplacedWithAnError() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        let fitting = Davy.Event.breadcrumb("checkout_started", info: ["note": String(repeating: "x", count: 1000)])
        let tooLarge = Davy.Event.breadcrumb("checkout_started", info: ["note": String(repeating: "x", count: 1001)])
        // IDs and timestamps have a fixed length, so any record of `fitting` has this size.
        let builder = RequestBuilder(configuration: DavyConfiguration(endpoint: URL(string: "https://example.com/events")!))
        let size = try builder.size(of: EventRecord(fitting, timestamp: .now), in: Session.start(environment: nil))
        let telemetry = makeDavy(directory: directory, transport: transport, maxEventSize: size)

        telemetry.log(fitting)
        telemetry.log(tooLarge)
        telemetry.log(.error("payment_declined", info: tooLarge.info))
        // Even the replacement error is too large, so nothing is sent.
        telemetry.log(.breadcrumb(String(repeating: "a", count: size)))
        await telemetry.flushAndWait()

        let events = await transport.sentEvents
        #expect(events.map(\.type) == ["breadcrumb", "error", "error"])
        #expect(events.map(\.name) == ["checkout_started", "davy_event_too_large", "davy_event_too_large"])
        #expect(events[0].info == fitting.info)
        #expect(events[1].info == ["rejected_name": "checkout_started", "size": String(size + 1)])
        // A replaced error keeps its diagnostics, but not the rest of its info.
        let replacedError = try #require(events[2].info)
        #expect(events[2].loggedInfo?.keys.sorted() == ["rejected_name", "size"])
        #expect(replacedError["rejected_name"] == "payment_declined")
        #expect(replacedError["low_power_mode"] == String(ProcessInfo.processInfo.isLowPowerModeEnabled))
    }

    @Test func retriedEventsKeepTheirID() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        await transport.setFailing(true)
        let telemetry = makeDavy(directory: directory, transport: transport)

        telemetry.log(.breadcrumb("event_0"))
        await telemetry.flushAndWait()
        await transport.setFailing(false)
        await telemetry.flushAndWait()

        let eventIDs = try await transport.requests.map { request in
            let body = try #require(request.httpBody)
            return try JSONDecoder().decode(ReceivedPayload.self, from: body).events.first?.id
        }
        #expect(eventIDs.count == 2)
        #expect(eventIDs[0] != nil)
        #expect(eventIDs[0] == eventIDs[1])
    }

    @Test(arguments: [400, 413, 414, 422, 431])
    func eventsTheServerRejectsAreDiscardedWithoutBlockingTheQueue(statusCode: Int) async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        await transport.respond(with: statusCode)
        let telemetry = makeDavy(directory: directory, transport: transport)

        telemetry.log(.breadcrumb("rejected"))
        await telemetry.flushAndWait()
        await transport.respond(with: 200)
        telemetry.log(.breadcrumb("accepted"))
        await telemetry.flushAndWait()

        #expect(await transport.attempts == 2)
        #expect(await transport.sentNames == ["accepted"])
    }

    @Test(arguments: [401, 403, 404, 408, 429, 500, 503])
    func eventsAreKeptAfterOtherErrorResponses(statusCode: Int) async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        await transport.respond(with: statusCode)
        let telemetry = makeDavy(directory: directory, transport: transport)

        telemetry.log(.breadcrumb("event_0"))
        await telemetry.flushAndWait()
        await transport.respond(with: 200)
        await telemetry.flushAndWait()

        #expect(await transport.attempts == 2)
        #expect(await transport.sentNames == ["event_0"])
    }

    @Test func retryAfterPausesSendingUntilItEndsEvenWhenFlushing() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        await transport.respond(with: 429, headers: ["Retry-After": "1"])
        let telemetry = makeDavy(directory: directory, transport: transport)

        telemetry.log(.breadcrumb("event_0"))
        await telemetry.flushAndWait()
        await transport.respond(with: 200)
        telemetry.log(.breadcrumb("event_1"))
        await telemetry.flushAndWait()
        #expect(await transport.attempts == 1)

        #expect(await waitUntil { await transport.sentNames == ["event_0", "event_1"] })
    }

    @Test func retryAfterPauseSurvivesRelaunch() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let throttlingTransport = MockTransport()
        await throttlingTransport.respond(with: 503, headers: ["Retry-After": "3600"])
        do {
            let telemetry = makeDavy(directory: directory, transport: throttlingTransport)
            telemetry.log(.breadcrumb("event_0"))
            await telemetry.flushAndWait()
        }

        let transport = MockTransport()
        let relaunched = makeDavy(directory: directory, transport: transport)
        relaunched.log(.breadcrumb("event_1"))
        await relaunched.flushAndWait()

        #expect(await throttlingTransport.attempts == 1)
        #expect(await transport.attempts == 0)
    }

    @Test func retryAfterIsReadAsSecondsOrAnHTTPDate() throws {
        let now = try Date("2015-10-21T07:27:00Z", strategy: .iso8601)
        func retryAfter(_ headers: [String: String]) throws -> TimeInterval? {
            let url = try #require(URL(string: "https://example.com"))
            let response = try #require(HTTPURLResponse(url: url, statusCode: 429, httpVersion: nil, headerFields: headers))
            return response.retryAfter(now: now)
        }

        #expect(try retryAfter(["Retry-After": "120"]) == 120)
        #expect(try retryAfter(["Retry-After": "Wed, 21 Oct 2015 07:28:00 GMT"]) == 60)
        #expect(try retryAfter(["Retry-After": "Wednesday, 21-Oct-15 07:28:00 GMT"]) == 60)
        #expect(try retryAfter(["Retry-After": "Wed Oct 21 07:28:00 2015"]) == 60)
        #expect(try retryAfter(["Retry-After": "Sun Nov  1 07:27:00 2015"]) == 11 * 24 * 60 * 60)
        // An HTTP date is measured from the server's clock when the response includes one.
        #expect(try retryAfter(["Retry-After": "Wed, 21 Oct 2015 07:28:00 GMT", "Date": "Wed, 21 Oct 2015 07:27:30 GMT"]) == 30)
        #expect(try retryAfter(["Retry-After": "soon"]) == nil)
        #expect(try retryAfter(["Retry-After": "-5"]) == nil)
        #expect(try retryAfter([:]) == nil)
    }

    @Test func debugSnapshotRecordsEachRequestWithItsResponse() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        await transport.setFailing(true)
        let telemetry = makeDavy(directory: directory, transport: transport, headers: ["Authorization": "Bearer token"])

        telemetry.log(.breadcrumb("checkout_started"))
        telemetry.log(.breadcrumb("checkout_finished"))
        let start = Date.now
        await telemetry.flushAndWait()
        let pending = await telemetry.debugSnapshot().pending
        await transport.setFailing(false)
        await telemetry.flushAndWait()
        let requests = await telemetry.debugSnapshot().requests

        try #require(requests.count == 2)
        #expect(requests.map(\.statusCode) == [500, 200])
        #expect(requests.map(\.outcome) == [.failed(reason: "HTTP 500"), .delivered])
        for request in requests {
            #expect(request.headers == ["Accept-Language": "*", "User-Agent": "Davy", "Content-Type": "application/json", "Authorization": "Bearer token"])
            #expect(request.eventIDs == pending.map(\.id))
            #expect(request.body.contains(#""name":"checkout_finished""#))
            #expect((start...Date.now).contains(request.sentAt))
            #expect(request.duration >= 0)
        }
    }

    @Test(arguments: [
        (429, ["Retry-After": "120"], DavyDebugSnapshot.Request.Outcome.throttled(retryAfter: 120)),
        (400, [:], .rejected),
    ])
    func debugSnapshotRecordsThrottledAndRejectedRequests(
        statusCode: Int,
        headers: [String: String],
        outcome: DavyDebugSnapshot.Request.Outcome
    ) async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        await transport.respond(with: statusCode, headers: headers)
        let telemetry = makeDavy(directory: directory, transport: transport)
        telemetry.log(.breadcrumb("checkout_started"))
        await telemetry.flushAndWait()

        // The server's Retry-After is recorded, not the randomly lengthened pause.
        let request = try #require(await telemetry.debugSnapshot().requests.first)
        #expect(request.statusCode == statusCode)
        #expect(request.outcome == outcome)
    }

    @Test func debugSnapshotRecordsRequestsThatGetNoResponse() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        await transport.setOffline(true)
        let telemetry = makeDavy(directory: directory, transport: transport)

        telemetry.log(.breadcrumb("checkout_started"))
        await telemetry.flushAndWait()
        let snapshot = await telemetry.debugSnapshot()

        let request = try #require(snapshot.requests.first)
        let reason = try #require(snapshot.lastFailure?.reason)
        #expect(request.statusCode == nil)
        #expect(request.outcome == .failed(reason: reason))
        #expect(request.body.contains(#""name":"checkout_started""#))
    }

    @Test func requestHistoryIsLimitedAndCanBeCleared() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        let telemetry = makeDavy(
            directory: directory,
            transport: transport,
            batchSize: 1,
            debugHistoryLimit: 2
        )

        for name in ["first", "second", "third"] {
            telemetry.log(.breadcrumb(name))
        }
        await telemetry.flushAndWait()
        let sentIDs = await telemetry.debugSnapshot().sent.map(\.event.id)
        #expect(await telemetry.debugSnapshot().requests.map(\.eventIDs) == [[sentIDs[0]], [sentIDs[1]]])

        telemetry.clearDebugHistory()
        #expect(await telemetry.debugSnapshot().requests.isEmpty)
    }

    @Test func debugSnapshotListsUnsentEventsAsPending() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        let telemetry = makeDavy(directory: directory, transport: transport, info: ["edition": "uk"])

        telemetry.log(.breadcrumb("checkout_started"))
        telemetry.log(.error("payment_declined", info: ["reason": "card_expired"]))
        let snapshot = await telemetry.debugSnapshot()

        #expect(snapshot.pending.map(\.name) == ["checkout_started", "payment_declined"])
        #expect(snapshot.pending.map(\.isError) == [false, true])
        #expect(snapshot.pending.map { $0.info["reason"] } == [nil, "card_expired"])
        #expect(snapshot.pending.map(\.sessionInfo) == [["edition": "uk"], ["edition": "uk"]])
        #expect(snapshot.pending.map(\.sessionID) == [telemetry.sessionID, telemetry.sessionID])
        #expect(Set(snapshot.pending.map(\.id)).count == 2)
        #expect(snapshot.sent.isEmpty)
        #expect(snapshot.lastFailure == nil)
        #expect(await transport.attempts == 0)
    }

    @Test(arguments: [
        (200, DavyDebugSnapshot.SentEvent.Outcome.delivered),
        (400, .rejected(statusCode: 400)),
    ])
    func debugSnapshotMovesEventsFromPendingToSentOnceTheServerResponds(
        statusCode: Int,
        outcome: DavyDebugSnapshot.SentEvent.Outcome
    ) async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        await transport.respond(with: statusCode)
        let telemetry = makeDavy(directory: directory, transport: transport)

        telemetry.log(.breadcrumb("checkout_started"))
        telemetry.log(.breadcrumb("checkout_finished"))
        let pending = await telemetry.debugSnapshot().pending
        let start = Date.now
        await telemetry.flushAndWait()
        let snapshot = await telemetry.debugSnapshot()

        #expect(snapshot.pending.isEmpty)
        #expect(snapshot.sent.map(\.event) == pending)
        #expect(snapshot.sent.map(\.outcome) == [outcome, outcome])
        #expect(snapshot.sent.allSatisfy { (start...Date.now).contains($0.sentAt) })
    }

    @Test(arguments: [(3, ["event_2", "event_3", "event_4"]), (0, [])])
    func debugHistoryKeepsOnlyTheMostRecentSentEvents(limit: Int, expectedNames: [String]) async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        let telemetry = makeDavy(directory: directory, transport: transport, batchSize: 2, debugHistoryLimit: limit)

        for index in 0..<5 {
            telemetry.log(.breadcrumb("event_\(index)"))
        }
        await telemetry.flushAndWait()
        #expect(await telemetry.debugSnapshot().sent.map(\.event.name) == expectedNames)

        telemetry.clearDebugHistory()
        #expect(await telemetry.debugSnapshot().sent.isEmpty)
    }

    @Test func debugSnapshotCountsEventsLoggedAndSentEachMinuteEvenWithoutHistory() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        let telemetry = makeDavy(directory: directory, transport: transport, debugHistoryLimit: 0)
        let start = DavyDebugSnapshot.Activity.minute(containing: .now)

        telemetry.log(.breadcrumb("checkout_started"))
        telemetry.log(.breadcrumb("checkout_finished"))
        let logged = await telemetry.debugSnapshot().activity
        #expect(logged.reduce(0) { $0 + $1.logged } == 2)
        #expect(logged.reduce(0) { $0 + $1.sent } == 0)

        await telemetry.flushAndWait()
        telemetry.clearDebugHistory()
        let sent = await telemetry.debugSnapshot().activity
        #expect(sent.reduce(0) { $0 + $1.logged } == 2)
        #expect(sent.reduce(0) { $0 + $1.sent } == 2)
        #expect(sent.allSatisfy { (start...Date.now).contains($0.minute) })
    }

    @Test func debugActivityCountsEventsLeftOverFromAnEarlierLaunchAsLogged() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        await transport.setFailing(true)
        do {
            let earlier = makeDavy(directory: directory, transport: transport)
            earlier.log(.breadcrumb("app_launched"))
            earlier.log(.breadcrumb("content_viewed"))
            #expect(await earlier.debugSnapshot().pending.count == 2)
        }

        let relaunched = makeDavy(directory: directory, transport: transport)
        relaunched.log(.breadcrumb("app_launched"))
        let snapshot = await relaunched.debugSnapshot()

        #expect(snapshot.pending.count == 3)
        #expect(snapshot.activity.reduce(0) { $0 + $1.logged } == 3)
    }

    @Test func debugCountsEveryLoggedEventAsPendingOrSent() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        var store = FileEventStore(directory: directory.appending(path: "events", directoryHint: .isDirectory))
        let session = Session.start(environment: nil)
        // Left over from earlier launches: one logged before the last hour, one expired, and one
        // that can't be decoded. Only the first can be sent, so only it counts as logged.
        for (name, loggedAt) in [("earlier", Date.now.addingTimeInterval(-2 * 60 * 60)), ("expired", Date.now.addingTimeInterval(-31 * 24 * 60 * 60))] {
            let pending = PendingEvent(
                session: session, event: EventRecord(.breadcrumb(name), timestamp: loggedAt),
                destination: RequestBuilder.destination(for: URL(string: "https://example.com/events")!)
            )
            try store.append(DavyJSON.encoder.encode(pending), loggedAt: loggedAt)
        }
        try store.append(Data(#"{"not":"an event"}"#.utf8), loggedAt: .now.addingTimeInterval(-60))

        let transport = MockTransport()
        await transport.setFailing(true)
        let telemetry = makeDavy(directory: directory, transport: transport)
        telemetry.log(.breadcrumb("checkout_started"))
        telemetry.log(.breadcrumb("checkout_finished"))
        await telemetry.flushAndWait()
        let failed = await telemetry.debugSnapshot()
        #expect(failed.loggedCount == 3)
        #expect(failed.pending.count == 3)
        #expect(failed.sentCount == 0)

        await transport.respond(with: 400)
        telemetry.log(.breadcrumb("checkout_abandoned"))
        await telemetry.flushAndWait()
        telemetry.clearDebugHistory()
        let sent = await telemetry.debugSnapshot()
        #expect(sent.loggedCount == 4)
        #expect(sent.pending.isEmpty)
        #expect(sent.sentCount == 4)
    }

    @Test func debugSnapshotListsWhenEachSessionLoggedItsFirstEvent() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        await transport.setFailing(true)
        let earlierSessionID: UUID
        let earlierFirstLogged: Date
        do {
            let earlier = makeDavy(directory: directory, transport: transport)
            earlierSessionID = earlier.sessionID
            earlier.log(.breadcrumb("app_launched"))
            earlier.log(.breadcrumb("content_viewed"))
            earlierFirstLogged = try #require(await earlier.debugSnapshot().pending.first).timestamp
        }

        let relaunched = makeDavy(directory: directory, transport: transport)
        #expect(await relaunched.debugSnapshot().sessionStarts.map(\.sessionID) == [earlierSessionID])

        relaunched.log(.breadcrumb("app_launched"))
        relaunched.log(.breadcrumb("content_viewed"))
        let snapshot = await relaunched.debugSnapshot()
        let relaunchedFirstLogged = try #require(snapshot.pending.first { $0.sessionID == relaunched.sessionID }).timestamp
        let starts = Dictionary(uniqueKeysWithValues: snapshot.sessionStarts.map { ($0.sessionID, $0.date) })

        #expect(Set(starts.keys) == [earlierSessionID, relaunched.sessionID])
        // Stored timestamps have millisecond precision.
        #expect(try abs(#require(starts[earlierSessionID]).timeIntervalSince(earlierFirstLogged)) < 0.001)
        #expect(try abs(#require(starts[relaunched.sessionID]).timeIntervalSince(relaunchedFirstLogged)) < 0.001)
    }

    @Test func failedSendsLeaveEventsPendingAndAreRecordedUntilASendSucceeds() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        await transport.setFailing(true)
        let telemetry = makeDavy(directory: directory, transport: transport)

        telemetry.log(.breadcrumb("checkout_started"))
        let start = Date.now
        await telemetry.flushAndWait()
        let failed = await telemetry.debugSnapshot()

        #expect(failed.pending.map(\.name) == ["checkout_started"])
        #expect(failed.sent.isEmpty)
        let failure = try #require(failed.lastFailure)
        #expect(failure.reason == "HTTP 500")
        #expect((start...Date.now).contains(failure.date))

        await transport.setFailing(false)
        await telemetry.flushAndWait()
        let sent = await telemetry.debugSnapshot()
        #expect(sent.pending.isEmpty)
        #expect(sent.sent.map(\.event) == failed.pending)
        #expect(sent.lastFailure == nil)
    }

    @Test(.timeLimit(.minutes(1)))
    func debugUpdatesYieldASnapshotNowAndAfterEachChange() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        let telemetry = makeDavy(directory: directory, transport: transport)
        var updates = telemetry.debugUpdates().makeAsyncIterator()

        let initial = await updates.next()
        #expect(initial == .empty)

        telemetry.log(.breadcrumb("checkout_started"))
        let logged = try #require(await updates.next())
        #expect(logged.pending.map(\.name) == ["checkout_started"])

        await telemetry.flushAndWait()
        let sent = try #require(await updates.next())
        #expect(sent.pending.isEmpty)
        #expect(sent.sent.map(\.event) == logged.pending)
    }

    @Test(arguments: [204, 307])
    func `Transport sends fixed headers and does not follow redirects`(status: Int) async throws {
        let serverSocket = socket(AF_INET, SOCK_STREAM, 0)
        try #require(serverSocket >= 0)
        defer { close(serverSocket) }

        var address = sockaddr_in()
        address.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        address.sin_family = sa_family_t(AF_INET)
        address.sin_port = 0
        address.sin_addr.s_addr = inet_addr("127.0.0.1")
        let bound = withUnsafePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                bind(serverSocket, $0, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        try #require(bound == 0)
        try #require(listen(serverSocket, 1) == 0)

        var length = socklen_t(MemoryLayout<sockaddr_in>.size)
        let named = withUnsafeMutablePointer(to: &address) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) {
                getsockname(serverSocket, $0, &length)
            }
        }
        try #require(named == 0)
        let port = UInt16(bigEndian: address.sin_port)

        async let receivedTask = Task.detached { () -> (requestLine: String, headers: [String: String]) in
            let client = accept(serverSocket, nil, nil)
            defer { close(client) }
            var buffer = Data()
            var byte: UInt8 = 0
            while read(client, &byte, 1) == 1 {
                buffer.append(byte)
                if buffer.suffix(4) == Data("\r\n\r\n".utf8) { break }
            }
            let lines = String(decoding: buffer, as: UTF8.self).components(separatedBy: "\r\n")
            let requestLine = lines.first ?? ""
            var headers: [String: String] = [:]
            for line in lines.dropFirst() where !line.isEmpty {
                let parts = line.split(separator: ":", maxSplits: 1)
                if parts.count == 2 {
                    headers[parts[0].lowercased()] = parts[1].trimmingCharacters(in: .whitespaces)
                }
            }
            if let contentLength = headers["content-length"].flatMap(Int.init), contentLength > 0 {
                var remaining = contentLength
                var chunk = [UInt8](repeating: 0, count: 1024)
                while remaining > 0 {
                    let count = read(client, &chunk, min(remaining, chunk.count))
                    if count <= 0 { break }
                    remaining -= count
                }
            }
            let statusLine = status == 204 ? "204 No Content" : "307 Temporary Redirect"
            let location = status == 307 ? "Location: http://127.0.0.1:1/other\r\n" : ""
            let response = Data("HTTP/1.1 \(statusLine)\r\n\(location)Content-Length: 0\r\nConnection: close\r\n\r\n".utf8)
            _ = response.withUnsafeBytes { write(client, $0.baseAddress, $0.count) }
            return (requestLine, headers)
        }.value

        let endpoint = try #require(URL(string: "http://127.0.0.1:\(port)/events"))
        let builder = RequestBuilder(
            configuration: DavyConfiguration(
                endpoint: endpoint,
                headers: [
                    "X-Api-Key": "secret",
                    "user-agent": "Custom/1.0",
                    "accept-language": "en-GB",
                    "content-type": "text/plain",
                    "cOoKiE": "session=secret",
                ]
            )
        )
        let request = try builder.request(
            session: Session.start(environment: nil),
            events: [EventRecord(.breadcrumb("checkout_started"), timestamp: .now)],
            sentAt: .now
        )
        await #expect(throws: DavyError.insecureEndpoint) {
            try await URLSessionTransport().send(request)
        }
        let response = try await URLSessionTransport(allowsInsecureHTTP: true).send(request)
        let received = await receivedTask

        #expect(response.statusCode == status)
        #expect(received.requestLine == "POST /events HTTP/1.1")
        #expect(received.headers["content-type"] == "application/json")
        #expect(received.headers["user-agent"] == "Davy")
        #expect(received.headers["accept-language"] == "*")
        #expect(received.headers["x-api-key"] == "secret")
        #expect(received.headers["cookie"] == nil)
    }

    #if os(macOS)
    @Test func measureLogsABreadcrumbWithItsDurationEvenWhenTheBodyThrows() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        let telemetry = makeDavy(directory: directory, transport: transport)

        #expect(telemetry.measure("sync_work") { 42 } == 42)
        struct Failure: Error {}
        #expect(throws: Failure.self) {
            try telemetry.measure("failing_work") { () throws -> Void in throw Failure() }
        }
        let value = await telemetry.measure("async_work") {
            await Task.yield()
            return "done"
        }
        #expect(value == "done")
        await telemetry.flushAndWait()

        let events = await transport.sentEvents
        #expect(events.map(\.name) == ["sync_work", "failing_work", "async_work"])
        #expect(events.map(\.type) == ["breadcrumb", "breadcrumb", "breadcrumb"])
        for event in events {
            let milliseconds = try #require(event.info?["duration_ms"].flatMap(Int.init))
            #expect(milliseconds >= 0)
        }
    }

    @Test func errorsLoggedFromAnErrorIncludeItsTypeInInfo() async throws {
        let directory = makeTemporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = MockTransport()
        let telemetry = makeDavy(directory: directory, transport: transport)

        telemetry.log(DavyError.invalidResponse, name: "request_failed", info: ["error_type": "custom", "stage": "upload"])
        await telemetry.flushAndWait()

        let event = try #require(await transport.sentEvents.first)
        #expect(event.type == "error")
        #expect(event.name == "request_failed")
        #expect(event.loggedInfo == ["error_type": "DavyError", "stage": "upload"])
    }

    @Test func invalidEventNameCrashesWhenConfiguredTo() async {
        await #expect(processExitsWith: .failure) {
            let telemetry = makeDavy(
                directory: makeTemporaryDirectory(),
                transport: MockTransport(),
                onInvalidEventName: .crash
            )
            telemetry.log(.breadcrumb("checkoutStarted"))
        }
    }
    #endif
}
