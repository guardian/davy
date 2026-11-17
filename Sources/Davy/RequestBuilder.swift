import Foundation

/// Builds the HTTP request that delivers events in the configured
/// ``DavyConfiguration/RequestFormat``.
struct RequestBuilder: Sendable {
    let endpoint: URL
    let headers: [String: String]
    let format: DavyConfiguration.RequestFormat

    /// Characters left as is in query strings and paths; everything else, including `+`, `&`,
    /// `=`, and `/`, is percent-encoded so every value decodes the same way on every server.
    private static let unreservedCharacters = CharacterSet(
        charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"
    )

    private static let infoEncoder: JSONEncoder = {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys, .withoutEscapingSlashes]
        return encoder
    }()

    init(configuration: DavyConfiguration) {
        self.endpoint = configuration.endpoint
        self.headers = configuration.headers
        self.format = configuration.format
    }

    /// Builds a request delivering `events`, all logged in `session`, stamped with `sentAt`, the
    /// device's time when it's sent. A
    /// ``DavyConfiguration/RequestFormat/jsonEvent(maxConcurrentRequests:)`` or
    /// ``DavyConfiguration/RequestFormat/queryString(method:maxConcurrentRequests:)`` request
    /// carries exactly one event.
    func request(session: Session, events: [EventRecord], sentAt: Date) throws -> URLRequest {
        var request: URLRequest
        switch format {
        case .jsonBatch, .jsonEvent:
            if case .jsonEvent = format, events.count != 1 {
                throw DavyError.invalidRequest
            }
            request = URLRequest(url: endpoint)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.httpBody = try DavyJSON.encoder.encode(DavyPayload(sentAt: sentAt, session: session, events: events))
        case let .queryString(method, _):
            guard events.count == 1, let event = events.first else {
                throw DavyError.invalidRequest
            }
            request = URLRequest(url: try queryStringURL(session: session, event: event, sentAt: sentAt))
            request.httpMethod = method.rawValue
            // Every request has to reach the server to be logged, so never answer one from a cache.
            request.cachePolicy = .reloadIgnoringLocalCacheData
        }
        for (field, value) in headers {
            request.setValue(value, forHTTPHeaderField: field)
        }
        return request
    }

    /// The size of `event` in bytes: the length of the URL it's sent to with
    /// ``DavyConfiguration/RequestFormat/queryString(method:maxConcurrentRequests:)``,
    /// whatever the configured format. `sentAt` is always the same length, so any date will do.
    func size(of event: EventRecord, in session: Session) throws -> Int {
        try queryStringURL(session: session, event: event, sentAt: .now).absoluteString.utf8.count
    }

    /// `<endpoint>/<name>?eventId=…&type=…&timestamp=…&sentAt=…&sessionId=…`, keeping any query
    /// items already in the endpoint. Parameters whose value is `nil` are omitted.
    private func queryStringURL(session: Session, event: EventRecord, sentAt: Date) throws -> URL {
        guard var components = URLComponents(url: endpoint, resolvingAgainstBaseURL: true) else {
            throw DavyError.invalidRequest
        }
        let path = components.percentEncodedPath
        components.percentEncodedPath = (path.hasSuffix("/") ? path : path + "/") + Self.percentEncoded(event.name)

        let parameters: [(name: String, value: String?)] = [
            ("eventId", event.id.uuidString),
            ("type", event.type.rawValue),
            ("timestamp", DavyJSON.timestamp(event.timestamp)),
            ("sentAt", DavyJSON.timestamp(sentAt)),
            ("sessionId", session.id.uuidString),
            ("environment", session.environment),
            ("appVersion", session.appVersion),
            ("build", session.build),
            ("os", session.os),
            ("osVersion", session.osVersion),
            ("deviceModel", session.deviceModel),
            ("locale", session.locale),
            ("sessionInfo", try session.info.map { String(decoding: try Self.infoEncoder.encode($0), as: UTF8.self) }),
            ("info", try event.info.map { String(decoding: try Self.infoEncoder.encode($0), as: UTF8.self) }),
        ]
        let query = parameters.compactMap { name, value in
            value.map { "\(name)=\(Self.percentEncoded($0))" }
        }
        let existingQuery = components.percentEncodedQuery.map { [$0] } ?? []
        components.percentEncodedQuery = (existingQuery.filter { !$0.isEmpty } + query).joined(separator: "&")

        guard let url = components.url else {
            throw DavyError.invalidRequest
        }
        return url
    }

    private static func percentEncoded(_ string: String) -> String {
        string.addingPercentEncoding(withAllowedCharacters: unreservedCharacters) ?? ""
    }
}
