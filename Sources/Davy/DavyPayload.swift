import Foundation

/// The JSON request body: `{ "session": { … }, "events": [ … ] }`.
///
/// Every request contains events from a single session, so `session` describes the app and device
/// exactly as they were when those events were logged.
struct DavyPayload: Encodable {
    let session: Session
    let events: [EventRecord]
}

/// An event as persisted on disk, together with the session it was logged in, so events left over
/// from a previous launch are still sent with their original session.
struct PendingEvent: Codable, Sendable {
    let session: Session
    let event: EventRecord
}

/// The fixed JSON encoding used for both storage and requests, so every app sends the same shape.
enum DavyJSON {
    /// How timestamps are written: ISO 8601 in UTC with fractional seconds, for example
    /// `2026-11-09T11:38:55.780Z`.
    static let timestampStyle = Date.ISO8601FormatStyle(includingFractionalSeconds: true)

    static let encoder = makeEncoder()

    /// Like ``encoder``, but indented with sorted keys, for showing events in ``DavyDebugView``.
    static let prettyEncoder = makeEncoder(outputFormatting: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])

    private static func makeEncoder(outputFormatting: JSONEncoder.OutputFormatting = []) -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.outputFormatting = outputFormatting
        encoder.dateEncodingStrategy = .custom { date, encoder in
            var container = encoder.singleValueContainer()
            try container.encode(date.formatted(timestampStyle))
        }
        return encoder
    }

    static let decoder: JSONDecoder = {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { decoder in
            let string = try decoder.singleValueContainer().decode(String.self)
            return try Date(string, strategy: timestampStyle)
        }
        return decoder
    }()
}
