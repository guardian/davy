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

/// Records each way `request` breaks the spec as an issue.
private func expectConforms(_ request: URLRequest, to spec: OpenAPISpec, sourceLocation: SourceLocation = #_sourceLocation) {
    for violation in spec.violations(of: request) {
        Issue.record("\(request.httpMethod ?? "") \(request.url?.absoluteString ?? ""): \(violation)", sourceLocation: sourceLocation)
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
        struct Expected: Decodable {
            let method: String
            let url: String
            let headers: [String: String]
        }

        let name: String
        let endpoint: URL
        let headers: [String: String]
        let sentAt: Date
        let session: Session
        let events: [EventRecord]
        let expected: Expected

        enum CodingKeys: String, CodingKey {
            case name, endpoint, headers, session, events, expected
            case sentAt = "sent_at"
        }
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
            let builder = RequestBuilder(configuration: .init(endpoint: fixture.endpoint, headers: fixture.headers))
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

    @Test func everyKindOfEventIsSentAsTheSpecDescribes() async throws {
        let spec = try OpenAPISpec.load()
        let directory = FileManager.default.temporaryDirectory
            .appending(path: "DavyTests-\(UUID().uuidString)", directoryHint: .isDirectory)
        defer { try? FileManager.default.removeItem(at: directory) }
        let transport = RecordingTransport()
        let telemetry = Davy(
            configuration: DavyConfiguration(
                endpoint: endpoint,
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
        telemetry.log(.breadcrumb("large_event", info: ["blob": .string(String(repeating: "x", count: 5000))]))
        await telemetry.flushAndWait()

        let requests = await transport.requests
        let sentEvents = try requests.reduce(0) { count, request in
            let body = try #require(request.httpBody)
            let payload = try #require(JSONSerialization.jsonObject(with: body) as? [String: Any])
            return count + ((payload["events"] as? [Any])?.count ?? 0)
        }
        #expect(sentEvents == 7)
        for request in requests {
            expectConforms(request, to: spec)
        }
    }

    @Test func sessionsWithAndWithoutOptionalFieldsAreSentAsTheSpecDescribes() throws {
        let spec = try OpenAPISpec.load()
        let builder = RequestBuilder(configuration: DavyConfiguration(endpoint: endpoint))
        let sessions = [
            Session(
                id: UUID(), environment: "production", appVersion: "1.2.0", build: 45,
                os: "ios", osVersion: "26.1.0", deviceModel: "iPhone17,1", locale: "es_419",
                info: ["edition": "uk", "tier": "supporter"]
            ),
            Session(
                id: UUID(), environment: nil, appVersion: nil, build: nil,
                os: "watchos", osVersion: "12.0.0", deviceModel: nil, locale: nil
            ),
        ]
        // A whole second, which is still sent with milliseconds.
        let event = EventRecord(.breadcrumb("checkout_started"), timestamp: Date(timeIntervalSince1970: 1_700_000_000))

        for session in sessions {
            expectConforms(try builder.request(session: session, events: [event], sentAt: .now), to: spec)
        }
    }

    @Test(arguments: [
        "2023-11-14T22:13:25.000Z",
        "2023-11-14T22:13:25.007Z",
    ])
    func `Send times use the event timestamp format without replacing event times`(
        sentAt: String
    ) throws {
        let timestamp = "2023-11-14T22:13:20.000Z"
        let event = EventRecord(.breadcrumb("checkout_started"), timestamp: try Date(timestamp, strategy: DavyJSON.timestampStyle))
        let session = Session.start(environment: nil)
        let builder = RequestBuilder(configuration: .init(endpoint: endpoint))
        let request = try builder.request(
            session: session, events: [event], sentAt: Date(sentAt, strategy: DavyJSON.timestampStyle)
        )

        let body = try #require(request.httpBody)
        let payload = try #require(JSONSerialization.jsonObject(with: body) as? [String: Any])
        #expect(payload["sent_at"] as? String == sentAt)
        #expect(payload["sentAt"] == nil)
        let events = try #require(payload["events"] as? [[String: Any]])
        #expect(events.first?["timestamp"] as? String == timestamp)
        expectConforms(request, to: try OpenAPISpec.load())
    }

    @Test func everyStatusCodeIsHandledAsTheSpecDescribes() throws {
        let spec = try OpenAPISpec.load()
        #expect(spec.operations.count == 1)

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
                case ("Stored", .delivered, .delivered),
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
        case extraSessionField
        case objectInInfo
        case noEvents
        case missingSentAt
        case sentAtWithoutMilliseconds
        case wrongUserAgent
        case wrongAcceptLanguage
    }

    @Test(arguments: Breakage.allCases)
    func requestsThatBreakTheSpecAreCaught(_ breakage: Breakage) throws {
        let spec = try OpenAPISpec.load()
        let session = Session(
            id: UUID(), environment: "beta", appVersion: "1.2.0", build: 45,
            os: "ios", osVersion: "26.1.0", deviceModel: "iPhone17,1", locale: "en_GB"
        )
        let events = [EventRecord(.error("payment_declined", info: ["reason": "card_expired"]), timestamp: .now)]
        let batch = try RequestBuilder(configuration: DavyConfiguration(endpoint: endpoint))
            .request(session: session, events: events, sentAt: .now)
        #expect(spec.violations(of: batch).isEmpty)

        let broken = switch breakage {
        case .lowercaseEventID:
            try changingBody(of: batch) { payload in
                var events = payload["events"] as? [[String: Any]] ?? []
                events[0]["id"] = (events[0]["id"] as? String)?.lowercased()
                payload["events"] = events
            }
        case .timestampWithoutMilliseconds:
            try changingBody(of: batch) { payload in
                var events = payload["events"] as? [[String: Any]] ?? []
                events[0]["timestamp"] = "2026-11-09T11:38:55Z"
                payload["events"] = events
            }
        case .unknownOS:
            try changingBody(of: batch) { payload in
                var session = payload["session"] as? [String: Any] ?? [:]
                session["os"] = "Windows"
                payload["session"] = session
            }
        case .emptyInfo:
            try changingBody(of: batch) { payload in
                var events = payload["events"] as? [[String: Any]] ?? []
                events[0]["info"] = [:] as [String: String]
                payload["events"] = events
            }
        case .emptyName:
            try changingBody(of: batch) { payload in
                var events = payload["events"] as? [[String: Any]] ?? []
                events[0]["name"] = ""
                payload["events"] = events
            }
        case .missingSessionID:
            try changingBody(of: batch) { payload in
                var session = payload["session"] as? [String: Any] ?? [:]
                session["id"] = nil
                payload["session"] = session
            }
        case .extraSessionField:
            try changingBody(of: batch) { payload in
                var session = payload["session"] as? [String: Any] ?? [:]
                session["deviceId"] = "123"
                payload["session"] = session
            }
        case .objectInInfo:
            try changingBody(of: batch) { payload in
                var events = payload["events"] as? [[String: Any]] ?? []
                events[0]["info"] = ["payment": ["method": "apple_pay"]]
                payload["events"] = events
            }
        case .noEvents:
            try changingBody(of: batch) { $0["events"] = [] }
        case .missingSentAt:
            try changingBody(of: batch) { $0["sent_at"] = nil }
        case .sentAtWithoutMilliseconds:
            try changingBody(of: batch) { $0["sent_at"] = "2026-11-09T11:39:00Z" }
        case .wrongUserAgent:
            {
                var request = batch
                request.setValue("Davy/1.0 CFNetwork/3826.500.131 Darwin/25.0.0", forHTTPHeaderField: "User-Agent")
                return request
            }()
        case .wrongAcceptLanguage:
            {
                var request = batch
                request.setValue("en-GB,en;q=0.9", forHTTPHeaderField: "Accept-Language")
                return request
            }()
        }
        #expect(!spec.violations(of: broken).isEmpty)
    }

    @Test func sessionsStoredWithAStringBuildStillDecode() throws {
        func decode(build: String) throws -> Session {
            let json = #"{"id":"6F9619FF-8B86-D011-B42D-00C04FC964FF","build":\#(build),"os":"ios","osVersion":"26.1.0"}"#
            return try DavyJSON.decoder.decode(Session.self, from: Data(json.utf8))
        }
        #expect(try decode(build: "45").build == 45)
        #expect(try decode(build: #""45""#).build == 45)
        #expect(try decode(build: #""1.2.3""#).build == nil)
    }

    @Test func sessionsStoredWithCamelCaseKeysStillDecode() throws {
        let json = #"{"id":"6F9619FF-8B86-D011-B42D-00C04FC964FF","appVersion":"1.2.0","os":"ios","osVersion":"26.1.0","deviceModel":"iPhone17,1"}"#
        let session = try DavyJSON.decoder.decode(Session.self, from: Data(json.utf8))
        #expect(session.appVersion == "1.2.0")
        #expect(session.osVersion == "26.1.0")
        #expect(session.deviceModel == "iPhone17,1")

        let encoded = try #require(JSONSerialization.jsonObject(with: DavyJSON.encoder.encode(session)) as? [String: Any])
        #expect(Set(encoded.keys) == ["id", "app_version", "os", "os_version", "device_model"])
    }
}
