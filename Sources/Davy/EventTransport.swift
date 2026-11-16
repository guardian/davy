import Foundation

/// Errors produced when building or delivering a request.
enum DavyError: Error {
    case invalidRequest
    case invalidResponse
}

/// Sends a request. Throwing means no HTTP response was received, for example because the device
/// is offline; the events are kept and retried.
protocol EventTransport: Sendable {
    func send(_ request: URLRequest) async throws -> HTTPURLResponse
}

extension EventTransport {
    /// Sends `request` and classifies the result, with the response's status code if one was
    /// received.
    func deliver(_ request: URLRequest) async -> (outcome: DeliveryOutcome, statusCode: Int?) {
        do {
            let response = try await send(request)
            return (DeliveryOutcome(response), response.statusCode)
        } catch {
            return (.failed("\(error)"), nil)
        }
    }
}

/// Sends requests with `URLSession.davy`.
struct URLSessionTransport: EventTransport {
    var session: URLSession = .davy

    func send(_ request: URLRequest) async throws -> HTTPURLResponse {
        let (_, response) = try await session.data(for: request)
        guard let response = response as? HTTPURLResponse else {
            throw DavyError.invalidResponse
        }
        return response
    }
}

extension URLSession {
    /// A session that never caches responses or stores cookies, so every request reaches the
    /// server and requests can't be linked to each other by a cookie.
    static let davy: URLSession = {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.urlCache = nil
        configuration.requestCachePolicy = .reloadIgnoringLocalCacheData
        configuration.httpCookieStorage = nil
        configuration.httpShouldSetCookies = false
        return URLSession(configuration: configuration)
    }()
}

/// What a response means for the events in its request.
enum DeliveryOutcome: Sendable {
    /// A 2xx response: the events were accepted.
    case delivered
    /// The server will never accept these events, so they are discarded rather than blocking the
    /// queue: 400 Bad Request, 413 Content Too Large, 414 URI Too Long, 422 Unprocessable Content,
    /// or 431 Request Header Fields Too Large.
    case rejected(statusCode: Int)
    /// A 429 Too Many Requests or 503 Service Unavailable response with a valid `Retry-After`
    /// header: send nothing for this many seconds.
    case throttled(retryAfter: TimeInterval)
    /// Anything else, such as a network error, a 5xx response, or a 401, 403, or 404 response
    /// that likely means the endpoint is misconfigured: keep the events and retry with backoff.
    case failed(String)

    static let rejectedStatusCodes: Set<Int> = [400, 413, 414, 422, 431]

    init(_ response: HTTPURLResponse, now: Date = .now) {
        let statusCode = response.statusCode
        switch statusCode {
        case 200..<300:
            self = .delivered
        case 429, 503:
            if let retryAfter = response.retryAfter(now: now) {
                self = .throttled(retryAfter: retryAfter)
            } else {
                self = .failed("HTTP \(statusCode)")
            }
        case _ where Self.rejectedStatusCodes.contains(statusCode):
            self = .rejected(statusCode: statusCode)
        default:
            self = .failed("HTTP \(statusCode)")
        }
    }
}

extension DavyDebugSnapshot.Request.Outcome {
    init(_ outcome: DeliveryOutcome) {
        switch outcome {
        case .delivered:
            self = .delivered
        case .rejected:
            self = .rejected
        case let .throttled(retryAfter):
            self = .throttled(retryAfter: retryAfter)
        case let .failed(reason):
            self = .failed(reason: reason)
        }
    }
}

extension HTTPURLResponse {
    /// The number of seconds to wait from the `Retry-After` header, given either as a number of
    /// seconds or as an HTTP date. `nil` if the header is missing or invalid.
    ///
    /// An HTTP date is measured from the response's `Date` header when present, so a wrong device
    /// clock doesn't change how long to wait.
    func retryAfter(now: Date = .now) -> TimeInterval? {
        guard let header = value(forHTTPHeaderField: "Retry-After")?.trimmingCharacters(in: .whitespaces),
              !header.isEmpty else {
            return nil
        }
        if header.utf8.allSatisfy({ (UInt8(ascii: "0")...UInt8(ascii: "9")).contains($0) }) {
            return TimeInterval(header)
        }
        guard let date = HTTPDate.parse(header) else { return nil }
        let reference = value(forHTTPHeaderField: "Date").flatMap(HTTPDate.parse) ?? now
        return max(0, date.timeIntervalSince(reference))
    }
}

/// Parses the date formats that RFC 9110 requires HTTP recipients to accept.
enum HTTPDate {
    private static let formats = [
        "EEE, dd MMM yyyy HH:mm:ss 'GMT'", // IMF-fixdate: Sun, 06 Nov 1994 08:49:37 GMT
        "EEEE, dd-MMM-yy HH:mm:ss 'GMT'", // Obsolete RFC 850: Sunday, 06-Nov-94 08:49:37 GMT
        "EEE MMM d HH:mm:ss yyyy", // Obsolete asctime: Sun Nov  6 08:49:37 1994
    ]

    static func parse(_ string: String) -> Date? {
        // asctime pads single-digit days with a second space.
        let string = string.split(separator: " ").joined(separator: " ")
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.timeZone = .gmt
        for format in formats {
            formatter.dateFormat = format
            if let date = formatter.date(from: string) {
                return date
            }
        }
        return nil
    }
}
