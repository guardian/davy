import Foundation
import Yams

/// A value read from JSON or YAML.
enum JSONValue: Equatable, Sendable, CustomStringConvertible {
    case null
    case bool(Bool)
    case number(Double)
    case string(String)
    case array([JSONValue])
    case object([String: JSONValue])

    struct UnsupportedValue: Error {
        let type: String
    }

    /// Converts the output of `JSONSerialization` or Yams.
    init(_ value: Any) throws {
        switch value {
        case is NSNull:
            self = .null
        case let string as String:
            self = .string(string)
        case let number as NSNumber:
            self = CFGetTypeID(number) == CFBooleanGetTypeID() ? .bool(number.boolValue) : .number(number.doubleValue)
        case let array as [Any]:
            self = .array(try array.map(JSONValue.init))
        case let object as [AnyHashable: Any]:
            self = .object(try Dictionary(uniqueKeysWithValues: object.map { key, value in
                (String(describing: key.base), try JSONValue(value))
            }))
        default:
            throw UnsupportedValue(type: "\(type(of: value))")
        }
    }

    subscript(key: String) -> JSONValue? {
        object?[key]
    }

    var string: String? {
        if case let .string(string) = self { string } else { nil }
    }

    var number: Double? {
        if case let .number(number) = self { number } else { nil }
    }

    var array: [JSONValue]? {
        if case let .array(array) = self { array } else { nil }
    }

    var object: [String: JSONValue]? {
        if case let .object(object) = self { object } else { nil }
    }

    func isOfType(_ type: String) -> Bool {
        switch (type, self) {
        case ("null", .null), ("boolean", .bool), ("number", .number), ("string", .string), ("array", .array), ("object", .object):
            true
        case let ("integer", .number(number)):
            number.rounded() == number
        default:
            false
        }
    }

    var description: String {
        switch self {
        case .null: "null"
        case let .bool(bool): String(bool)
        case let .number(number): number.rounded() == number && abs(number) < 1e15 ? String(Int(number)) : String(number)
        case let .string(string): "\"\(string)\""
        case let .array(array): "[\(array.map(\.description).joined(separator: ", "))]"
        case let .object(object): "{\(object.sorted { $0.key < $1.key }.map { "\"\($0.key)\": \($0.value)" }.joined(separator: ", "))}"
        }
    }
}

/// `Spec/openapi.yaml`, with just enough of OpenAPI 3.1 and JSON Schema to check requests against it.
///
/// Anything in the spec this doesn't understand, such as a new schema keyword, is reported as a
/// violation rather than skipped, so no part of the spec silently goes unchecked.
struct OpenAPISpec {
    static let fileURL = URL(filePath: #filePath)
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .deletingLastPathComponent()
        .appending(path: "Spec/openapi.yaml")

    /// Keywords that only describe a schema. In JSON Schema 2020-12, `format` is an annotation too,
    /// so the spec uses `pattern` for formats that must be checked.
    private static let annotations: Set<String> = ["description", "examples", "title", "format", "default", "deprecated", "$comment"]
    private static let methods = ["get", "put", "post", "delete", "options", "head", "patch", "trace"]

    let document: JSONValue

    static func load() throws -> OpenAPISpec {
        let yaml = try String(contentsOf: fileURL, encoding: .utf8)
        return OpenAPISpec(document: try JSONValue(Yams.load(yaml: yaml) ?? NSNull()))
    }

    /// Every operation, named like `"POST /events"`.
    var operations: [(name: String, operation: JSONValue)] {
        (document["paths"]?.object ?? [:]).sorted { $0.key < $1.key }.flatMap { path, item in
            Self.methods.compactMap { method in
                item[method].map { ("\(method.uppercased()) \(path)", $0) }
            }
        }
    }

    /// The name of the response component `operation` uses for `statusCode`, such as `"Rejected"`,
    /// preferring an exact code, then a range such as `2XX`, then `default`, as OpenAPI does.
    func responseName(for statusCode: Int, in operation: JSONValue) -> String? {
        let responses = operation["responses"]
        let response = responses?[String(statusCode)] ?? responses?["\(statusCode / 100)XX"] ?? responses?["default"]
        return response?["$ref"]?.string?.split(separator: "/").last.map(String.init)
    }

    /// Every way `request` breaks the spec, or an empty array if it conforms.
    func violations(of request: URLRequest) -> [String] {
        guard let url = request.url,
              let components = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let method = request.httpMethod?.lowercased() else {
            return ["The request has no URL or method"]
        }
        guard let server = document["servers"]?.array?.first?["url"]?.string,
              let serverComponents = URLComponents(string: server),
              components.scheme == serverComponents.scheme,
              components.host == serverComponents.host,
              components.port == serverComponents.port,
              components.percentEncodedPath.hasPrefix(serverComponents.percentEncodedPath) else {
            return ["The URL isn't on the spec's server"]
        }
        let path = String(components.percentEncodedPath.dropFirst(serverComponents.percentEncodedPath.count))
        guard let pathItem = document["paths"]?[path] else {
            return ["No path in the spec matches \(path)"]
        }
        guard let operation = pathItem[method] else {
            return ["\(path) has no \(method.uppercased()) operation"]
        }
        let queryViolations = components.percentEncodedQuery == nil ? [] : ["The request has a query string, which the spec doesn't allow"]
        return queryViolations + parameterViolations(of: request, operation: operation) + bodyViolations(of: request, operation: operation)
    }

    private func parameterViolations(of request: URLRequest, operation: JSONValue) -> [String] {
        var violations: [String] = []
        for entry in operation["parameters"]?.array ?? [] {
            guard let parameter = resolve(entry),
                  parameter["in"] == .string("header"),
                  let name = parameter["name"]?.string else {
                violations.append("The spec uses a parameter OpenAPISpec doesn't support: \(entry)")
                continue
            }
            guard let value = request.value(forHTTPHeaderField: name) else {
                if parameter["required"] == .bool(true) {
                    violations.append("The request is missing the required header \(name)")
                }
                continue
            }
            if let schema = parameter["schema"] {
                violations += schemaViolations(of: .string(value), against: schema, at: "header \(name)")
            }
        }
        return violations
    }

    private func bodyViolations(of request: URLRequest, operation: JSONValue) -> [String] {
        let body = request.httpBody ?? Data()
        guard let reference = operation["requestBody"] else {
            return body.isEmpty ? [] : ["The request has a body, but the spec doesn't define one"]
        }
        guard let requestBody = resolve(reference) else {
            return ["The spec's requestBody can't be resolved"]
        }
        guard !body.isEmpty else {
            return requestBody["required"] == .bool(true) ? ["The request has no body, but the spec requires one"] : []
        }
        let contentType = request.value(forHTTPHeaderField: "Content-Type")?
            .split(separator: ";").first?
            .trimmingCharacters(in: .whitespaces) ?? ""
        guard let media = requestBody["content"]?[contentType] else {
            return ["The Content-Type \"\(contentType)\" isn't in the spec"]
        }
        guard contentType == "application/json" else {
            return ["Only JSON bodies are supported by OpenAPISpec"]
        }
        guard let json = try? JSONValue(JSONSerialization.jsonObject(with: body)) else {
            return ["The body isn't valid JSON"]
        }
        return media["schema"].map { schemaViolations(of: json, against: $0, at: "body") } ?? []
    }

    /// `value` itself, or the value its `$ref`, such as `#/components/schemas/Event`, points to.
    private func resolve(_ value: JSONValue) -> JSONValue? {
        guard let reference = value["$ref"]?.string else { return value }
        guard reference.hasPrefix("#/") else { return nil }
        return reference.dropFirst(2).split(separator: "/").reduce(document as JSONValue?) { current, token in
            current?[token.replacingOccurrences(of: "~1", with: "/").replacingOccurrences(of: "~0", with: "~")]
        }
    }

    func schemaViolations(of value: JSONValue, against schema: JSONValue, at location: String) -> [String] {
        switch schema {
        case .bool(true):
            return []
        case .bool(false):
            return ["\(location) isn't allowed"]
        case .object:
            break
        default:
            return ["\(location)'s schema isn't an object or a boolean"]
        }

        var violations: [String] = []
        for (keyword, argument) in (schema.object ?? [:]).sorted(by: { $0.key < $1.key }) {
            switch keyword {
            case "$ref":
                // In JSON Schema 2020-12, `$ref` applies alongside the keywords next to it.
                guard let target = resolve(.object(["$ref": argument])) else {
                    violations.append("\(location)'s schema $ref \(argument) can't be resolved")
                    continue
                }
                violations += schemaViolations(of: value, against: target, at: location)
            case "type":
                let types = argument.string.map { [$0] } ?? argument.array?.compactMap(\.string) ?? []
                if !types.contains(where: value.isOfType) {
                    violations.append("\(location) isn't of type \(types.joined(separator: " or ")): \(value)")
                }
            case "enum":
                if !(argument.array ?? []).contains(value) {
                    violations.append("\(location) isn't one of \(argument): \(value)")
                }
            case "const":
                if value != argument {
                    violations.append("\(location) isn't \(argument): \(value)")
                }
            case "pattern":
                guard let string = value.string else { continue }
                guard let pattern = argument.string, let regex = try? NSRegularExpression(pattern: pattern) else {
                    violations.append("\(location)'s schema pattern \(argument) isn't a valid regular expression")
                    continue
                }
                if regex.firstMatch(in: string, range: NSRange(string.startIndex..., in: string)) == nil {
                    violations.append("\(location) doesn't match \(pattern): \(value)")
                }
            case "minLength":
                if let string = value.string, let minimum = argument.number, Double(string.unicodeScalars.count) < minimum {
                    violations.append("\(location) is shorter than \(argument) characters: \(value)")
                }
            case "minItems":
                if let array = value.array, let minimum = argument.number, Double(array.count) < minimum {
                    violations.append("\(location) has fewer than \(argument) items")
                }
            case "minProperties":
                if let object = value.object, let minimum = argument.number, Double(object.count) < minimum {
                    violations.append("\(location) has fewer than \(argument) properties")
                }
            case "required":
                guard let object = value.object else { continue }
                for name in (argument.array ?? []).compactMap(\.string) where object[name] == nil {
                    violations.append("\(location) is missing the required property \(name)")
                }
            case "properties":
                guard let object = value.object, let properties = argument.object else { continue }
                for (name, propertySchema) in properties.sorted(by: { $0.key < $1.key }) {
                    if let property = object[name] {
                        violations += schemaViolations(of: property, against: propertySchema, at: "\(location).\(name)")
                    }
                }
            case "additionalProperties":
                guard let object = value.object else { continue }
                let declared = Set(schema["properties"]?.object?.keys.map { $0 } ?? [])
                for (name, property) in object.sorted(by: { $0.key < $1.key }) where !declared.contains(name) {
                    violations += schemaViolations(of: property, against: argument, at: "\(location).\(name)")
                }
            case "items":
                guard let array = value.array else { continue }
                for (index, item) in array.enumerated() {
                    violations += schemaViolations(of: item, against: argument, at: "\(location)[\(index)]")
                }
            case _ where Self.annotations.contains(keyword):
                break
            default:
                violations.append("\(location)'s schema uses \(keyword), which OpenAPISpec doesn't support, so add support for it")
            }
        }
        return violations
    }
}
