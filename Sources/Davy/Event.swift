import Foundation

extension Davy {
    /// Something that happened in the app: either a breadcrumb or an error, identified by a name
    /// you choose.
    ///
    /// Names are for counting and grouping across many sessions, so keep them short and stable, for
    /// example `"checkout_started"` or `"payment_declined"`. Don't include personal data or values
    /// that vary per user. Use ``DavyConfiguration/onInvalidEventName`` to enforce lower snake
    /// case names.
    ///
    /// Use `info` for additional details, for example `["payment_method": "apple_pay"]`. The same
    /// rules apply: no personal data.
    public enum Event: Sendable, Hashable {
        /// A step the user or app took, for example `"checkout_started"`.
        case breadcrumb(String, info: [String: String] = [:])
        /// The fact that an error occurred, for example `"payment_declined"`.
        ///
        /// When an error is logged, the app's CPU and memory usage, the memory it has left, the
        /// device's thermal state, and whether Low Power Mode is on are added to `info` under
        /// `cpu_percent`, `memory_used_mb`, `memory_available_mb`, `thermal_state`, and
        /// `low_power_mode`, replacing any values already there.
        case error(String, info: [String: String] = [:])
    }
}

extension Davy.Event {
    /// The name of the error sent in place of an event with an invalid name when
    /// ``DavyConfiguration/onInvalidEventName`` is ``DavyConfiguration/InvalidEventNameAction/sendError``.
    static let invalidNameErrorName = "davy_invalid_event_name"

    /// The name of the error sent in place of an event over ``DavyConfiguration/maxEventSize``.
    static let tooLargeErrorName = "davy_event_too_large"

    /// The `info` key holding the rejected name in an ``invalidNameErrorName`` or
    /// ``tooLargeErrorName`` error.
    static let rejectedNameInfoKey = "rejected_name"

    /// The `info` key holding the rejected event's size in bytes in a ``tooLargeErrorName`` error.
    static let sizeInfoKey = "size"

    /// Logged when the app returns to the foreground, if
    /// ``DavyConfiguration/logsAppLifecycleEvents`` is set.
    static let appForegrounded = Self.breadcrumb("app_foregrounded")

    /// Logged when the app moves to the background, if
    /// ``DavyConfiguration/logsAppLifecycleEvents`` is set.
    static let appBackgrounded = Self.breadcrumb("app_backgrounded")

    /// Logged by ``Davy/addSessionInfo(_:)`` after updating the session's `info`.
    static let sessionUpdatedName = "session_updated"

    var name: String {
        switch self {
        case let .breadcrumb(name, _), let .error(name, _):
            name
        }
    }

    var info: [String: String] {
        switch self {
        case let .breadcrumb(_, info), let .error(_, info):
            info
        }
    }

    /// Whether `name` is lowercase ASCII letters and digits in words separated by single
    /// underscores, starting with a letter, for example `"checkout_started"` or `"step_2"`.
    static func isLowerSnakeCase(_ name: String) -> Bool {
        let underscore = UInt8(ascii: "_")
        var previous: UInt8?
        for byte in name.utf8 {
            switch byte {
            case UInt8(ascii: "a")...UInt8(ascii: "z"):
                break
            case UInt8(ascii: "0")...UInt8(ascii: "9"):
                guard previous != nil else { return false }
            case underscore:
                guard let previous, previous != underscore else { return false }
            default:
                return false
            }
            previous = byte
        }
        return previous != nil && previous != underscore
    }
}

/// A logged ``Davy/Event`` as it is sent in the request's `events` array:
///
/// ```json
/// {
///   "id": "…", "type": "error", "name": "payment_declined",
///   "info": { "reason": "card_expired", "cpu_percent": "12", "memory_used_mb": "143", "memory_available_mb": "2650", "thermal_state": "nominal", "low_power_mode": "false" },
///   "timestamp": "2026-11-09T11:38:55.780Z"
/// }
/// ```
struct EventRecord: Codable, Sendable, Hashable {
    enum EventType: String, Codable, Sendable {
        case breadcrumb
        case error
    }

    /// A random identifier generated when the event is logged and sent unchanged with every
    /// attempt, so the server can discard copies of an event that was delivered more than once.
    let id: UUID
    let type: EventType
    let name: String
    /// Additional details, including an error's ``Diagnostics``. `nil` when empty, so the key is
    /// omitted from the JSON.
    let info: [String: String]?
    /// When the event was logged. Encoded as ISO 8601 with fractional seconds.
    let timestamp: Date

    init(_ event: Davy.Event, timestamp: Date, id: UUID = UUID()) {
        self.id = id
        switch event {
        case .breadcrumb:
            self.type = .breadcrumb
        case .error:
            self.type = .error
        }
        self.name = event.name
        self.info = event.info.isEmpty ? nil : event.info
        self.timestamp = timestamp
    }
}
