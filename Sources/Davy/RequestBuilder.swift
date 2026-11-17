import Foundation

/// Builds the HTTP request that delivers events to the configured endpoint.
struct RequestBuilder: Sendable {
    let endpoint: URL
    let headers: [String: String]

    init(configuration: DavyConfiguration) {
        self.endpoint = configuration.endpoint
        self.headers = configuration.headers
    }

    /// Builds a `POST` request delivering `events`, all logged in `session`, stamped with `sentAt`,
    /// the device's time when it's sent.
    func request(session: Session, events: [EventRecord], sentAt: Date) throws -> URLRequest {
        var request = URLRequest(url: endpoint)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try DavyJSON.encoder.encode(DavyPayload(sentAt: sentAt, session: session, events: events))
        for (field, value) in headers {
            request.setValue(value, forHTTPHeaderField: field)
        }
        return request
    }

    /// The size of `event` in bytes: the UTF-8 length of a single-event JSON request body with
    /// `session`. `sentAt` is always the same length, so any date will do.
    func size(of event: EventRecord, in session: Session) throws -> Int {
        try DavyJSON.encoder.encode(DavyPayload(sentAt: .now, session: session, events: [event])).count
    }
}
