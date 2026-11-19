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
    /// How many events were discarded without being sent because they expired, couldn't be
    /// read, or couldn't be decoded.
    private(set) var discardedCount = 0

    init(directory: URL) {
        self.directory = directory
        let fileManager = FileManager.default
        do {
            try fileManager.createDirectory(at: directory, withIntermediateDirectories: true)
        } catch {
            Self.logger.error("Failed to create event directory: \(error)")
        }
        // Set on every launch, so directories created by earlier versions are excluded too.
        do {
            try directory.excludeFromBackup()
        } catch {
            Self.logger.error("Failed to exclude event directory from backups: \(error)")
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

    /// Reads up to `limit` of the oldest events.
    ///
    /// Empty files, which can never be sent, are discarded. Files that exist but can't be read
    /// right now are kept and skipped, so they're read again next time. For example, data
    /// protection can lock files while the device is locked. They expire after `maxEventAge` if
    /// they never become readable.
    mutating func oldest(_ limit: Int) -> [StoredEvent] {
        var events: [StoredEvent] = []
        var broken: [String] = []
        var unreadableCount = 0
        for name in fileNames {
            guard events.count < limit else { break }
            let data: Data
            do {
                data = try Data(contentsOf: url(for: name))
            } catch CocoaError.fileReadNoSuchFile {
                broken.append(name)
                continue
            } catch {
                unreadableCount += 1
                continue
            }
            guard !data.isEmpty else {
                broken.append(name)
                continue
            }
            events.append(StoredEvent(
                fileName: name,
                loggedAt: Self.loggedAt(of: name) ?? .distantPast,
                data: data
            ))
        }
        if unreadableCount > 0 {
            Self.logger.error("Couldn't read \(unreadableCount) event file(s); keeping them to try again later")
        }
        if !broken.isEmpty {
            Self.logger.error("Discarding \(broken.count) empty or missing event file(s)")
            discard(fileNames: broken)
        }
        return events
    }

    /// Deletes events the server responded to.
    mutating func remove(_ events: [StoredEvent]) {
        remove(fileNames: events.map(\.fileName))
    }

    /// Deletes events that can't be sent, such as ones that can't be decoded.
    mutating func discard(_ events: [StoredEvent]) {
        discard(fileNames: events.map(\.fileName))
    }

    /// Deletes events logged before `cutoff`.
    mutating func prune(olderThan cutoff: Date) {
        let expired = fileNames.filter { (Self.loggedAt(of: $0) ?? .distantPast) < cutoff }
        guard !expired.isEmpty else { return }
        Self.logger.info("Pruning \(expired.count) expired event(s)")
        discard(fileNames: expired)
    }

    /// Deletes every event.
    mutating func discardAll() {
        discard(fileNames: fileNames)
    }

    /// Deletes the oldest events until at most `limit` remain, not counting `excluded`, such as
    /// events in a request in flight. Returns how many were deleted.
    mutating func discardOldest(keeping limit: Int, excluding excluded: Set<String>) -> Int {
        let candidates = fileNames.filter { !excluded.contains($0) }
        let excess = candidates.count - limit
        guard excess > 0 else { return 0 }
        let count = fileNames.count
        discard(fileNames: Array(candidates.prefix(excess)))
        return count - fileNames.count
    }

    private mutating func discard(fileNames names: [String]) {
        let count = fileNames.count
        remove(fileNames: names)
        discardedCount += count - fileNames.count
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

extension URL {
    /// Keeps this file, or this directory and everything in it, out of iCloud and device backups,
    /// and Time Machine on macOS.
    ///
    /// Pending events only make sense on the device that logged them. A backup could restore them,
    /// with their session data, onto another device, which would then send them.
    func excludeFromBackup() throws {
        var url = self
        var values = URLResourceValues()
        values.isExcludedFromBackup = true
        try url.setResourceValues(values)
    }
}
