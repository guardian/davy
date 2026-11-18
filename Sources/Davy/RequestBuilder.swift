import CryptoKit
import Foundation

/// Builds the HTTP request that delivers events to the configured endpoint.
struct RequestBuilder: Sendable {
    let endpoint: URL
    let headers: [String: String]
    let destination: String

    static func destination(for endpoint: URL) -> String {
        SHA256.hash(data: Data(endpoint.absoluteString.utf8)).map { String(format: "%02x", $0) }.joined()
    }

    init(configuration: DavyConfiguration) {
        self.endpoint = configuration.endpoint
        self.headers = configuration.headers
        self.destination = Self.destination(for: configuration.endpoint)
    }

    /// Builds a `POST` request delivering `events`, all logged in `session`, stamped with `sentAt`,
    /// the device's time when it's sent.
    func request(session: Session, events: [EventRecord], sentAt: Date) throws -> URLRequest {
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        for (field, value) in headers where !["cookie", "cookie2"].contains(field.lowercased()) {
            request.setValue(value, forHTTPHeaderField: field)
        }
        for field in ["Content-Type", "User-Agent", "Accept-Language"] {
            request.setValue(nil, forHTTPHeaderField: field)
        }
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.setValue("Davy", forHTTPHeaderField: "User-Agent")
        request.setValue("*", forHTTPHeaderField: "Accept-Language")
        request.httpBody = try DavyJSON.encoder.encode(DavyPayload(sentAt: sentAt, session: session, events: events))
        return request
    }

    /// The size of `event` in bytes: the UTF-8 length of a single-event JSON request body with
    /// `session`. `sentAt` is always the same length, so any date will do.
    func size(of event: EventRecord, in session: Session) throws -> Int {
        try DavyJSON.encoder.encode(DavyPayload(sentAt: .now, session: session, events: [event])).count
    }
}
