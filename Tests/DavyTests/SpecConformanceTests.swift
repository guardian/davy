import Foundation
import Testing
@testable import Davy

/// Records requests and accepts every one.
private actor RecordingTransport: EventTransport {
    private(set) var requests: [URLRequest] = []

    func send(_ request: URLRequest) async throws -> HTTPURLResponse {
        requests.append(request)
        let url = try #require(request.url)
        return try #require(HTTPURLResponse(url: url, statusCode: 200, httpVersion: nil, headerFields: nil))
    }
}

private struct CheckoutError: Error {}

/// The example endpoint used by the spec's server and paths.
private let endpoint = URL(string: "https://api.example.com/events")!

private let formats: [DavyConfiguration.RequestFormat] = [.jsonBatch, .jsonEvent(), .queryString(method: .post), .queryString(method: .get)]

/// Records each way `request` breaks the spec as an issue.
private func expectConforms(_ request: URLRequest, to spec: OpenAPISpec, sourceLocation: SourceLocation = #_sourceLocation) {
    for violation in spec.violations(of: request) {
        Issue.record("\(request.httpMethod ?? "") \(request.url?.absoluteString ?? ""): \(violation)", sourceLocation: sourceLocation)
    }
}

/// `request` with its percent-encoded query items changed by `change`.
private func changingQuery(of request: URLRequest, _ change: (inout [(name: String, value: String)]) -> Void) throws -> URLRequest {
    let url = try #require(request.url)
    var components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
    var items = (components.percentEncodedQuery ?? "").split(separator: "&").map { item in
        let parts = item.split(separator: "=", maxSplits: 1)
        return (name: String(parts[0]), value: String(parts[1]))
    }
    change(&items)
    components.percentEncodedQuery = items.map { "\($0.name)=\($0.value)" }.joined(separator: "&")
    var request = request
    request.url = components.url
    return request
}

private func changingQueryItem(_ name: String, of request: URLRequest, _ transform: (String) -> String) throws -> URLRequest {
    try changingQuery(of: request) { items in
        for index in items.indices where items[index].name == name {
            items[index].value = transform(items[index].value)
        }
    }
}

/// `request` with its JSON body changed by `change`.
private func changingBody(of request: URLRequest, _ change: (inout [String: Any]) -> Void) throws -> URLRequest {
    let body = try #require(request.httpBody)
    var payload = try #require(JSONSerialization.jsonObject(with: body) as? [String: Any])
    change(&payload)
    var request = request
    request.httpBody = try JSONSerialization.data(withJSONObject: payload)
    return request
}

/// Checks that Davy sends exactly the requests described by `Spec/openapi.yaml`, and handles the
/// responses it describes as it says.
struct SpecConformanceTests {
    /// A case in `Spec/fixtures.json`.
    private struct Fixture: Decodable {
        struct Format: Decodable {
            let type: String
            let method: String?
        }

        struct Expected: Decodable {
            let method: String
            let url: String
            let headers: [String: String]
        }

        let name: String
        let endpoint: URL
        let format: Format
        let headers: [String: String]
        let sentAt: Date
        let session: Session
        let events: [EventRecord]
        let expected: Expected
    }

    private struct Fixtures: Decodable {
        let cases: [Fixture]
    }

    @Test func requestsMatchTheSharedFixturesExactly() throws {
        let data = try Data(contentsOf: OpenAPISpec.fileURL.deletingLastPathComponent().appending(path: "fixtures.json"))
        let fixtures = try DavyJSON.decoder.decode(Fixtures.self, from: data).cases
        // The expected bodies, as any JSON.
        let rawCases = try #require((JSONSerialization.jsonObject(with: data) as? [String: Any])?["cases"] as? [[String: Any]])
        #expect(!fixtures.isEmpty)

        for (fixture, rawCase) in zip(fixtures, rawCases) {
            let format: DavyConfiguration.RequestFormat
            switch fixture.format.type {
            case "jsonBatch":
                format = .jsonBatch
            case "jsonEvent":
                format = .jsonEvent()
            case "queryString":
                format = .queryString(method: try #require(fixture.format.method.flatMap(DavyConfiguration.HTTPMethod.init)))
            default:
                Issue.record("Unknown format \(fixture.format.type) in \(fixture.name)")
                continue
            }
            let builder = RequestBuilder(configuration: .init(endpoint: fixture.endpoint, format: format, headers: fixture.headers))
            let request = try builder.request(session: fixture.session, events: fixture.events, sentAt: fixture.sentAt)

            let comment = Comment(rawValue: fixture.name)
            #expect(request.httpMethod == fixture.expected.method, comment)
            #expect(request.url?.absoluteString == fixture.expected.url, comment)
            let headers = Dictionary(uniqueKeysWithValues: (request.allHTTPHeaderFields ?? [:]).map { ($0.key.lowercased(), $0.value) })
            #expect(headers == Dictionary(uniqueKeysWithValues: fixture.expected.headers.map { ($0.key.lowercased(), $0.value) }), comment)
            let body = try request.httpBody.map { try JSONValue(JSONSerialization.jsonObject(with: $0)) }
            let expectedBody = try ((rawCase["expected"] as? [String: Any])?["body"]).map(JSONValue.init)
            #expect(body == expectedBody, comment)
        }
    }
    @Test(arguments: formats)
    func everyKindOfEventIsSentAsTheSpecDescribes(format: DavyConfiguration.RequestFormat) async throws {
        let spec = try OpenAPISpec.load()
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "DavyTests-\(UUID().uuidString)", directoryHint: .isDirectory)
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = RecordingTransport()
        let telemetry = Davy(
            configuration: DavyConfiguration(
                endpoint: endpoint,
                format: format,
                environment: "beta",
                info: ["edition": "uk"],
                storageDirectory: directory
            ),
            transport: transport,
            notificationCenter: nil
        )

        telemetry.log(.breadcrumb("checkout_started"))
        telemetry.log(.breadcrumb("payment_selected", info: ["payment_method": "apple_pay", "path": "a/b+c &=?#", "note": "café ☕️"]))
        // Sent as is, since `onInvalidEventName` defaults to `.send`.
        telemetry.log(.breadcrumb("Checkout Started/Now"))
        telemetry.log(.error("payment_declined", info: ["reason": "card_expired"]))
        telemetry.log(CheckoutError(), name: "checkout_failed")
        telemetry.measure("feed_load") {}
        // Replaced with a "davy_event_too_large" error.
        telemetry.log(.breadcrumb("large_event", info: ["blob": String(repeating: "x", count: 3000)]))
        await telemetry.flushAndWait()

        let requests = await transport.requests
        let sentEvents = try requests.reduce(0) { count, request in
            guard let body = request.httpBody else { return count + 1 }
            let payload = try #require(JSONSerialization.jsonObject(with: body) as? [String: Any])
            return count + ((payload["events"] as? [Any])?.count ?? 0)
        }
        #expect(sentEvents == 7)
        for request in requests {
            expectConforms(request, to: spec)
        }
    }

    @Test(arguments: formats)
    func sessionsWithAndWithoutOptionalFieldsAreSentAsTheSpecDescribes(format: DavyConfiguration.RequestFormat) throws {
        let spec = try OpenAPISpec.load()
        let builder = RequestBuilder(configuration: DavyConfiguration(endpoint: endpoint, format: format))
        let sessions = [
            Session(
                id: UUID(), environment: "production", appVersion: "1.2.0", build: "45",
                os: "iOS", osVersion: "26.1.0", deviceModel: "iPhone17,1", locale: "es_419",
                info: ["edition": "uk", "tier": "supporter"]
            ),
            Session(
                id: UUID(), environment: nil, appVersion: nil, build: nil,
                os: "watchOS", osVersion: "12.0.0", deviceModel: nil, locale: nil
            ),
        ]
        // A whole second, which is still sent with milliseconds.
        let event = EventRecord(.breadcrumb("checkout_started"), timestamp: Date(timeIntervalSince1970: 1_700_000_000))

        for session in sessions {
            expectConforms(try builder.request(session: session, events: [event], sentAt: .now), to: spec)
        }
    }

    @Test(arguments: formats, [
        "2023-11-14T22:13:25.000Z",
        "2023-11-14T22:13:25.007Z",
    ])
    func `Send times use the event timestamp format without replacing event times`(
        format: DavyConfiguration.RequestFormat, sentAt: String
    ) throws {
        let timestamp = "2023-11-14T22:13:20.000Z"
        let event = EventRecord(.breadcrumb("checkout_started"), timestamp: try Date(timestamp, strategy: DavyJSON.timestampStyle))
        let session = Session.start(environment: nil)
        let builder = RequestBuilder(configuration: .init(endpoint: endpoint, format: format))
        let request = try builder.request(
            session: session, events: [event], sentAt: Date(sentAt, strategy: DavyJSON.timestampStyle)
        )

        if let body = request.httpBody {
            let payload = try #require(JSONSerialization.jsonObject(with: body) as? [String: Any])
            #expect(payload["sentAt"] as? String == sentAt)
            #expect(payload["sent_at"] == nil)
            let events = try #require(payload["events"] as? [[String: Any]])
            #expect(events.first?["timestamp"] as? String == timestamp)
        } else {
            let url = try #require(request.url)
            let items = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems)
            #expect(items.filter { $0.name == "sentAt" }.map(\.value) == [sentAt])
            #expect(!items.contains { $0.name == "sent_at" })
            #expect(items.first { $0.name == "timestamp" }?.value == timestamp)
        }
        expectConforms(request, to: try OpenAPISpec.load())
    }

    @Test func everyStatusCodeIsHandledAsTheSpecDescribes() throws {
        let spec = try OpenAPISpec.load()
        #expect(spec.operations.count == 3)

        for (name, operation) in spec.operations {
            for statusCode in 100..<600 {
                let response = spec.responseName(for: statusCode, in: operation) ?? "nothing"
                let outcome = DeliveryOutcome(try #require(HTTPURLResponse(
                    url: endpoint, statusCode: statusCode, httpVersion: nil, headerFields: ["Retry-After": "120"]
                )))
                let outcomeWithoutRetryAfter = DeliveryOutcome(try #require(HTTPURLResponse(
                    url: endpoint, statusCode: statusCode, httpVersion: nil, headerFields: nil
                )))
                let isAsDescribed = switch (response, outcome, outcomeWithoutRetryAfter) {
                case ("Stored", .delivered, .delivered), ("StoredNoStore", .delivered, .delivered),
                     ("Rejected", .rejected, .rejected),
                     // Without a valid `Retry-After`, events are retried with backoff.
                     ("Throttled", .throttled, .failed),
                     ("Retried", .failed, .failed):
                    true
                default:
                    false
                }
                #expect(isAsDescribed, "\(name) \(statusCode): the spec says \(response), but Davy's outcome is \(outcome)")
            }
        }
    }

    /// Ways to break a conforming request, each caught by a different part of the spec.
    enum Breakage: CaseIterable {
        case lowercaseEventID
        case timestampWithoutMilliseconds
        case unknownOS
        case emptyInfo
        case emptyName
        case missingSessionID
        case unknownParameter
        case parametersOutOfOrder
        case unencodedCharacter
        case extraSessionField
        case numberInInfo
        case noEvents
        case missingSentAtInBody
        case sentAtWithoutMilliseconds
        case missingSentAtInQuery
    }

    @Test(arguments: Breakage.allCases)
    func requestsThatBreakTheSpecAreCaught(_ breakage: Breakage) throws {
        let spec = try OpenAPISpec.load()
        let session = Session(
            id: UUID(), environment: "beta", appVersion: "1.2.0", build: "45",
            os: "iOS", osVersion: "26.1.0", deviceModel: "iPhone17,1", locale: "en_GB"
        )
        let events = [EventRecord(.error("payment_declined", info: ["reason": "card_expired"]), timestamp: .now)]
        let queryString = try RequestBuilder(configuration: DavyConfiguration(endpoint: endpoint, format: .queryString()))
            .request(session: session, events: events, sentAt: .now)
        let batch = try RequestBuilder(configuration: DavyConfiguration(endpoint: endpoint))
            .request(session: session, events: events, sentAt: .now)
        #expect(spec.violations(of: queryString).isEmpty)
        #expect(spec.violations(of: batch).isEmpty)

        let broken = switch breakage {
        case .lowercaseEventID:
            try changingQueryItem("eventId", of: queryString) { $0.lowercased() }
        case .timestampWithoutMilliseconds:
            try changingQueryItem("timestamp", of: queryString) { _ in "2026-11-09T11%3A38%3A55Z" }
        case .unknownOS:
            try changingQueryItem("os", of: queryString) { _ in "Windows" }
        case .emptyInfo:
            try changingQueryItem("info", of: queryString) { _ in "%7B%7D" }
        case .emptyName:
            try {
                let url = try #require(queryString.url)
                var request = queryString
                request.url = URL(string: url.absoluteString.replacing("/events/payment_declined", with: "/events/"))
                return request
            }()
        case .missingSessionID:
            try changingQuery(of: queryString) { $0.removeAll { $0.name == "sessionId" } }
        case .unknownParameter:
            try changingQuery(of: queryString) { $0.append((name: "deviceId", value: "123")) }
        case .parametersOutOfOrder:
            try changingQuery(of: queryString) { $0.swapAt(0, 1) }
        case .unencodedCharacter:
            try changingQueryItem("timestamp", of: queryString) { $0.removingPercentEncoding ?? $0 }
        case .extraSessionField:
            try changingBody(of: batch) { payload in
                var session = payload["session"] as? [String: Any] ?? [:]
                session["deviceId"] = "123"
                payload["session"] = session
            }
        case .numberInInfo:
            try changingBody(of: batch) { payload in
                var events = payload["events"] as? [[String: Any]] ?? []
                events[0]["info"] = ["cpu_percent": 3]
                payload["events"] = events
            }
        case .noEvents:
            try changingBody(of: batch) { $0["events"] = [] }
        case .missingSentAtInBody:
            try changingBody(of: batch) { $0["sentAt"] = nil }
        case .sentAtWithoutMilliseconds:
            try changingBody(of: batch) { $0["sentAt"] = "2026-11-09T11:39:00Z" }
        case .missingSentAtInQuery:
            try changingQuery(of: queryString) { $0.removeAll { $0.name == "sentAt" } }
        }
        #expect(!spec.violations(of: broken).isEmpty)
    }
}
