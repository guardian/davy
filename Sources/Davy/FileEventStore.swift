import Foundation
import os

/// An encoded event read back from disk.
struct StoredEvent: Sendable {
    let fileName: String
    let loggedAt: Date
    let data: Data
}

/// Persists each event as its own file so writes are atomic and survive crashes.
///
/// Files are named `<loggedAtMillis>-<sequence>-<uuid>.json`, so sorting by name restores logging
/// order after a relaunch, and the logged date can be read without opening the file.
struct FileEventStore {
    private static let logger = Logger(subsystem: "Davy", category: "FileEventStore")
    private static let fileExtension = "json"

    let directory: URL
    /// Pending file names, oldest first.
    private var fileNames: [String]
    private var sequence: UInt64 = 0

    init(directory: URL) {
        self.directory = directory
        let fileManager = FileManager.default
        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        } catch {
            Self.logger.error("Failed to create event directory: \(error)")
        }
        let contents = (try? fileManager.contentsOfDirectory(atPath: directory.path(percentEncoded: false))) ?? []
        fileNames = contents.filter { $0.hasSuffix(".\(Self.fileExtension)") }.sorted()
    }

    var count: Int { fileNames.count }

    /// When each pending event was logged, oldest first, read from file names without opening
    /// the files.
    var loggedDates: [Date] { fileNames.compactMap(Self.loggedAt(of:)) }

    mutating func append(_ data: Data, loggedAt: Date) throws {
        let millis = Int64((loggedAt.timeIntervalSince1970 * 1000).rounded(.down))
        let name = String(format: "%015lld-%010llu-%@.%@", millis, sequence, UUID().uuidString, Self.fileExtension)
        sequence &+= 1
        try data.write(to: url(for: name), options: .atomic)
        fileNames.append(name)
    }

    /// Reads up to `limit` of the oldest events, skipping any in `excluded`. Unreadable files are
    /// discarded.
    mutating func oldest(_ limit: Int, excluding excluded: Set<String> = []) -> [StoredEvent] {
        var events: [StoredEvent] = []
        var unreadable: [String] = []
        for name in fileNames where !excluded.contains(name) {
            guard events.count < limit else { break }
            guard let data = try? Data(contentsOf: url(for: name)), !data.isEmpty else {
                unreadable.append(name)
                continue
            }
            events.append(StoredEvent(
                fileName: name,
                loggedAt: Self.loggedAt(of: name) ?? .distantPast,
                data: data
            ))
        }
        if !unreadable.isEmpty {
            Self.logger.error("Discarding \(unreadable.count) unreadable event file(s)")
            remove(fileNames: unreadable)
        }
        return events
    }

    mutating func remove(_ events: [StoredEvent]) {
        remove(fileNames: events.map(\.fileName))
    }

    /// Deletes events logged before `cutoff`.
    mutating func prune(olderThan cutoff: Date) {
        let expired = fileNames.filter { (Self.loggedAt(of: $0) ?? .distantPast) < cutoff }
        guard !expired.isEmpty else { return }
        Self.logger.info("Pruning \(expired.count) expired event(s)")
        remove(fileNames: expired)
    }

    private mutating func remove(fileNames names: [String]) {
        let names = Set(names)
        for name in names {
            try? FileManager.default.removeItem(at: url(for: name))
        }
        fileNames.removeAll { names.contains($0) }
    }

    private func url(for fileName: String) -> URL {
        directory.appending(path: fileName, directoryHint: .notDirectory)
    }

    private static func loggedAt(of fileName: String) -> Date? {
        guard let prefix = fileName.split(separator: "-", maxSplits: 1).first,
              let millis = Int64(prefix) else {
            return nil
        }
        return Date(timeIntervalSince1970: TimeInterval(millis) / 1000)
    }
}
