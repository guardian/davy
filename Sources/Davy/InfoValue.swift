import Foundation

extension Davy {
    /// A value in an event's or session's `info`: a string, a whole number, a decimal number, or a
    /// Boolean, sent as the matching JSON type.
    ///
    /// Literals convert automatically, so you can write
    /// `["payment_method": "apple_pay", "retries": 2, "cached": true]`. Wrap other values in the
    /// matching case, for example `.int(items.count)`.
    public enum InfoValue: Sendable, Hashable {
        case string(String)
        case int(Int)
        /// A decimal number. Infinity and NaN, which JSON can't represent, are sent as the strings
        /// `"inf"`, `"-inf"`, and `"nan"`.
        case double(Double)
        case bool(Bool)
    }
}

extension Davy.InfoValue: ExpressibleByStringInterpolation {
    public init(stringLiteral value: String) {
        self = .string(value)
    }
}

extension Davy.InfoValue: ExpressibleByIntegerLiteral {
    public init(integerLiteral value: Int) {
        self = .int(value)
    }
}

extension Davy.InfoValue: ExpressibleByFloatLiteral {
    public init(floatLiteral value: Double) {
        self = .double(value)
    }
}

extension Davy.InfoValue: ExpressibleByBooleanLiteral {
    public init(booleanLiteral value: Bool) {
        self = .bool(value)
    }
}

extension Davy.InfoValue: CustomStringConvertible {
    /// The value as text, for example `"apple_pay"`, `"2"`, `"0.5"`, or `"true"`.
    public var description: String {
        switch self {
        case let .string(value): value
        case let .int(value): String(value)
        case let .double(value): String(value)
        case let .bool(value): String(value)
        }
    }
}

extension Davy.InfoValue: Codable {
    public init(from decoder: any Decoder) throws {
        let container = try decoder.singleValueContainer()
        // Booleans first, so `true` isn't read as a number, and strings last, so events stored as
        // strings by earlier versions still decode.
        if let value = try? container.decode(Bool.self) {
            self = .bool(value)
        } else if let value = try? container.decode(Int.self) {
            self = .int(value)
        } else if let value = try? container.decode(Double.self) {
            self = .double(value)
        } else {
            self = .string(try container.decode(String.self))
        }
    }

    public func encode(to encoder: any Encoder) throws {
        var container = encoder.singleValueContainer()
        switch self {
        case let .string(value):
            try container.encode(value)
        case let .int(value):
            try container.encode(value)
        case let .double(value) where value.isFinite:
            try container.encode(value)
        case let .double(value):
            try container.encode(String(value))
        case let .bool(value):
            try container.encode(value)
        }
    }
}
