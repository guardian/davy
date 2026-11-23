import Foundation

/// Common data about the app and device, captured once when a session starts and sent as the
/// request's `session` object.
///
/// Fields are deliberately coarse: they describe the kind of device and app build an event came
/// from, not the person using it. Nothing here is persistent or unique to a device, such as a
/// device name, advertising or vendor identifier, time zone, or screen size.
struct Session: Codable, Sendable, Hashable {
    /// A random identifier, generated per session and never reused.
    let id: UUID
    /// ``DavyConfiguration/environment``, for example `"beta"`.
    let environment: String?
    /// The main bundle's `CFBundleShortVersionString`, for example `"1.2.0"`.
    let appVersion: String?
    /// The main bundle's `CFBundleVersion` as a number, for example `45`, or `nil` if it isn't a
    /// whole number, such as `"1.2.3"`.
    let build: Int?
    /// The operating system, for example `"iOS"` or `"macOS"`.
    let os: String
    /// The operating system version, for example `"26.1.0"`.
    let osVersion: String
    /// The hardware model identifier, for example `"iPhone17,1"` or `"Mac15,3"`, or `"Simulator"`.
    let deviceModel: String?
    /// The device's language and region, for example `"en_GB"`, without any custom locale settings.
    let locale: String?
    /// ``DavyConfiguration/info`` and any entries added with ``Davy/addSessionInfo(_:)``, omitted
    /// when empty.
    let info: [String: Davy.InfoValue]?

    init(
        id: UUID,
        environment: String?,
        appVersion: String?,
        build: Int?,
        os: String,
        osVersion: String,
        deviceModel: String?,
        locale: String?,
        info: [String: Davy.InfoValue]? = nil
    ) {
        self.id = id
        self.environment = environment
        self.appVersion = appVersion
        self.build = build
        self.os = os
        self.osVersion = osVersion
        self.deviceModel = deviceModel
        self.locale = locale
        self.info = info?.isEmpty == true ? nil : info
    }

    /// Returns a copy of this session with `additionalInfo` merged into ``info``, replacing any
    /// existing values for the same keys.
    func addingInfo(_ additionalInfo: [String: Davy.InfoValue]) -> Session {
        let merged = (info ?? [:]).merging(additionalInfo) { _, new in new }
        return Session(
            id: id,
            environment: environment,
            appVersion: appVersion,
            build: build,
            os: os,
            osVersion: osVersion,
            deviceModel: deviceModel,
            locale: locale,
            info: merged
        )
    }

    /// Captures the current app and device data for a new session.
    static func start(environment: String?, info: [String: Davy.InfoValue] = [:]) -> Session {
        Session(
            id: UUID(),
            environment: environment,
            appVersion: Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
            build: (Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String).flatMap { Int($0) },
            os: os,
            osVersion: osVersion,
            deviceModel: deviceModel,
            locale: locale,
            info: info
        )
    }

    private static var os: String {
        #if targetEnvironment(macCatalyst)
        "macCatalyst"
        #elseif os(macOS)
        "macOS"
        #elseif os(visionOS)
        "visionOS"
        #elseif os(tvOS)
        "tvOS"
        #elseif os(watchOS)
        "watchOS"
        #else
        "iOS"
        #endif
    }

    private static var osVersion: String {
        let version = ProcessInfo.processInfo.operatingSystemVersion
        return "\(version.majorVersion).\(version.minorVersion).\(version.patchVersion)"
    }

    private static var deviceModel: String? {
        #if targetEnvironment(simulator)
        "Simulator"
        #elseif os(macOS) || targetEnvironment(macCatalyst)
        sysctlString(named: "hw.model")
        #else
        sysctlString(named: "hw.machine")
        #endif
    }

    /// Language and region only, so locale customizations (calendar, numbering, and so on) aren't sent.
    private static var locale: String? {
        let locale = Locale.current
        guard let language = locale.language.languageCode?.identifier else { return nil }
        guard let region = locale.region?.identifier else { return language }
        return "\(language)_\(region)"
    }

    private static func sysctlString(named name: String) -> String? {
        var size = 0
        guard sysctlbyname(name, nil, &size, nil, 0) == 0, size > 0 else { return nil }
        var buffer = [CChar](repeating: 0, count: size)
        guard sysctlbyname(name, &buffer, &size, nil, 0) == 0 else { return nil }
        let bytes = buffer.prefix { $0 != 0 }.map { UInt8(bitPattern: $0) }
        return String(decoding: bytes, as: UTF8.self)
    }
}

extension Session {
    /// The JSON keys, in snake case, used both on the wire and on disk.
    enum CodingKeys: String, CodingKey {
        case id
        case environment
        case appVersion = "app_version"
        case build
        case os
        case osVersion = "os_version"
        case deviceModel = "device_model"
        case locale
        case info
    }

    /// The camel case keys that events stored by earlier versions use.
    private enum LegacyCodingKeys: String, CodingKey {
        case appVersion
        case osVersion
        case deviceModel
    }

    init(from decoder: any Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let legacy = try decoder.container(keyedBy: LegacyCodingKeys.self)
        let build: Int?
        // Events stored by earlier versions have `build` as a string.
        if let legacyBuild = try? container.decodeIfPresent(String.self, forKey: .build) {
            build = Int(legacyBuild)
        } else {
            build = try container.decodeIfPresent(Int.self, forKey: .build)
        }
        self.init(
            id: try container.decode(UUID.self, forKey: .id),
            environment: try container.decodeIfPresent(String.self, forKey: .environment),
            appVersion: try container.decodeIfPresent(String.self, forKey: .appVersion)
                ?? legacy.decodeIfPresent(String.self, forKey: .appVersion),
            build: build,
            os: try container.decode(String.self, forKey: .os),
            osVersion: try container.decodeIfPresent(String.self, forKey: .osVersion)
                ?? legacy.decode(String.self, forKey: .osVersion),
            deviceModel: try container.decodeIfPresent(String.self, forKey: .deviceModel)
                ?? legacy.decodeIfPresent(String.self, forKey: .deviceModel),
            locale: try container.decodeIfPresent(String.self, forKey: .locale),
            info: try container.decodeIfPresent([String: Davy.InfoValue].self, forKey: .info)
        )
    }
}
