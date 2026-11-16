import Foundation

/// The events a ``Davy`` instance is waiting to send, the events and requests it has recently
/// sent, how many events it has logged and sent, the most recent failure to send, and how many
/// events it logged and sent each minute over the last hour, for checking your events during
/// development.
///
/// Get one with ``Davy/debugSnapshot()``, or a new one each time something changes with
/// ``Davy/debugUpdates()``. ``DavyDebugView`` shows the same information.
public struct DavyDebugSnapshot: Sendable, Hashable {
    /// Events stored on disk that the server hasn't accepted or rejected yet, oldest first, which
    /// is the order they're sent in. Includes events left over from previous launches and events
    /// in requests that haven't finished.
    public let pending: [LoggedEvent]

    /// Events the server has accepted or rejected since this instance was created, oldest first,
    /// up to ``DavyConfiguration/debugHistoryLimit``.
    public let sent: [SentEvent]

    /// Requests that finished since this instance was created, in the order they finished, up to
    /// ``DavyConfiguration/debugHistoryLimit``.
    ///
    /// Includes requests that failed or were throttled, so you can see why events are still
    /// pending.
    public let requests: [Request]

    /// How many events this instance has logged, plus how many were left over from earlier
    /// launches when it was created.
    ///
    /// Each is either pending or sent, so this is always `pending.count + sentCount`. Events
    /// discarded without being sent, because they were older than
    /// ``DavyConfiguration/maxEventAge`` or couldn't be read, aren't counted.
    public let loggedCount: Int

    /// How many events the server has accepted or rejected since this instance was created,
    /// including events left over from earlier launches.
    ///
    /// Unlike ``sent``, this isn't limited by ``DavyConfiguration/debugHistoryLimit`` or reset by
    /// ``Davy/clearDebugHistory()``.
    public let sentCount: Int

    /// The most recent failure to send, if no request has succeeded since.
    public let lastFailure: Failure?

    /// How many events were logged and sent in each minute of the last hour, oldest first.
    /// Minutes in which nothing was logged or sent are left out.
    ///
    /// Includes events left over from earlier launches that hadn't been sent when this instance
    /// was created. Unlike ``sent``, this isn't limited by
    /// ``DavyConfiguration/debugHistoryLimit`` or emptied by ``Davy/clearDebugHistory()``.
    public let activity: [Activity]

    /// When each session's first event was logged, oldest first, for sessions whose first event
    /// was logged in the last hour.
    ///
    /// Only events this instance knows about count: its own, and events left over from earlier
    /// launches that were still pending when it was created.
    public let sessionStarts: [SessionStart]

    /// A snapshot with nothing pending, sent, or logged.
    public static let empty = DavyDebugSnapshot(
        pending: [],
        sent: [],
        requests: [],
        loggedCount: 0,
        sentCount: 0,
        lastFailure: nil,
        activity: [],
        sessionStarts: []
    )

    /// How far back ``activity`` goes.
    static let activityWindow: TimeInterval = 60 * 60

    /// The start of the oldest minute in ``activity`` at `date`.
    static func activityStart(at date: Date) -> Date {
        Activity.minute(containing: date).addingTimeInterval(-activityWindow)
    }
}

extension DavyDebugSnapshot {
    /// A logged event, with the ID, timestamp, and session it's sent with.
    public struct LoggedEvent: Identifiable, Sendable, Hashable {
        let record: EventRecord
        let session: Session

        /// A random identifier generated when the event was logged and sent unchanged with every
        /// attempt.
        public var id: UUID { record.id }

        /// The breadcrumb or error that was logged, after any replacement for an invalid name or
        /// for being over ``DavyConfiguration/maxEventSize``.
        public var event: Davy.Event {
            switch record.type {
            case .breadcrumb:
                .breadcrumb(record.name, info: info)
            case .error:
                .error(record.name, info: info)
            }
        }

        /// The event's name, for example `"checkout_started"`.
        public var name: String { record.name }

        /// The event's additional details.
        public var info: [String: String] { record.info ?? [:] }

        /// When the event was logged.
        public var timestamp: Date { record.timestamp }

        /// The ID of the session the event was logged in, which differs from
        /// ``Davy/sessionID`` for events left over from a previous launch.
        public var sessionID: UUID { session.id }

        var isError: Bool { record.type == .error }

        /// The event and its session as indented JSON with sorted keys, the way they're stored on
        /// disk.
        var prettyJSON: String {
            let pending = PendingEvent(session: session, event: record)
            guard let data = try? DavyJSON.prettyEncoder.encode(pending) else { return "" }
            return String(decoding: data, as: UTF8.self)
        }
    }

    /// An event the server responded to, so it's no longer pending.
    public struct SentEvent: Identifiable, Sendable, Hashable {
        /// How the server responded to an event.
        public enum Outcome: Sendable, Hashable {
            /// The server accepted the event with a 2xx response.
            case delivered
            /// The server permanently rejected the event, for example with 400 Bad Request, so it
            /// was discarded.
            case rejected(statusCode: Int)
        }

        /// The event that was sent.
        public let event: LoggedEvent
        /// When the server responded.
        public let sentAt: Date
        /// How the server responded.
        public let outcome: Outcome

        public var id: UUID { event.id }
    }

    /// A request that failed, leaving its events pending to be retried.
    public struct Failure: Sendable, Hashable {
        /// Why the request failed, for example `"HTTP 500"` or a network error.
        public let reason: String
        /// When the request failed.
        public let date: Date
    }

    /// An HTTP request Davy sent, with how the server responded.
    public struct Request: Identifiable, Sendable, Hashable {
        /// What the response meant for the request's events.
        public enum Outcome: Sendable, Hashable {
            /// The server accepted the events with a 2xx response.
            case delivered
            /// The server permanently rejected the events, for example with 400 Bad Request, so
            /// they were discarded.
            case rejected
            /// The server asked for a pause with `Retry-After`, so the events were kept.
            case throttled(retryAfter: TimeInterval)
            /// No response was received, or the response means the events should be retried, so
            /// they were kept. The reason is the same as ``Failure/reason``.
            case failed(reason: String)
        }

        /// A random identifier for the request.
        public let id: UUID
        /// The HTTP method, `POST` or `GET`.
        public let method: String
        /// The full URL, including any query string.
        public let url: URL
        /// The header fields Davy set: ``DavyConfiguration/headers`` and, for JSON bodies,
        /// `Content-Type`.
        public let headers: [String: String]
        /// The JSON body, or `nil` for
        /// ``DavyConfiguration/RequestFormat/queryString(method:maxConcurrentRequests:)`` requests,
        /// which have none.
        public let body: String?
        /// The IDs of the events in the request, oldest first.
        public let eventIDs: [UUID]
        /// When the request was sent.
        public let sentAt: Date
        /// How long until the response arrived or the request failed.
        public let duration: TimeInterval
        /// The response's HTTP status code, or `nil` if no response was received, for example
        /// because the device is offline.
        public let statusCode: Int?
        /// What the response meant for the request's events.
        public let outcome: Outcome
    }

    /// How many events were logged and sent in one minute.
    public struct Activity: Identifiable, Sendable, Hashable {
        /// The start of the minute.
        public let minute: Date
        /// How many events were logged during the minute, by this instance or, if they were still
        /// pending when it was created, by earlier launches.
        public internal(set) var logged: Int
        /// How many events the server accepted or rejected during the minute, including events
        /// left over from earlier launches.
        public internal(set) var sent: Int

        public var id: Date { minute }

        /// The start of the minute containing `date`.
        static func minute(containing date: Date) -> Date {
            Date(timeIntervalSinceReferenceDate: (date.timeIntervalSinceReferenceDate / 60).rounded(.down) * 60)
        }
    }

    /// When a session's first event was logged.
    public struct SessionStart: Identifiable, Sendable, Hashable {
        /// The session's ID, as in ``LoggedEvent/sessionID``.
        public let sessionID: UUID
        /// When the session's first event was logged.
        public let date: Date

        public var id: UUID { sessionID }
    }
}
