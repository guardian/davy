import Foundation
#if os(iOS) || os(tvOS) || os(visionOS)
import UIKit
#elseif os(macOS)
import AppKit
#endif

/// Reports when the app moves between the foreground and background, so pending events can be
/// flushed when it leaves the foreground.
///
/// Uses only APIs available to app extensions, so the package can be linked into one, such as a
/// Notification Service Extension.
enum AppLifecycle {
    /// Posted when the app enters the foreground (iOS) or becomes active (macOS), or `nil` where
    /// there's no app lifecycle to observe.
    static var foregroundNotification: Notification.Name? {
        #if os(iOS) || os(tvOS) || os(visionOS)
        UIApplication.willEnterForegroundNotification
        #elseif os(macOS)
        NSApplication.didBecomeActiveNotification
        #else
        nil
        #endif
    }

    /// Posted when the app moves to the background (iOS) or resigns active (macOS), or `nil` where
    /// there's no app lifecycle to observe.
    static var backgroundNotification: Notification.Name? {
        #if os(iOS) || os(tvOS) || os(visionOS)
        UIApplication.didEnterBackgroundNotification
        #elseif os(macOS)
        NSApplication.didResignActiveNotification
        #else
        nil
        #endif
    }

    private enum Transition {
        case foreground
        case background
    }

    /// Observes `center` until the returned task is cancelled, calling the handlers in the order
    /// the app moves between the foreground and background.
    ///
    /// Observers are registered before this returns, so no transition after that is missed.
    ///
    /// - Parameters:
    ///   - didEnterForeground: Called when the app returns to the foreground. Launching isn't
    ///     reported, so this is only called after `didEnterBackground`.
    ///   - didEnterBackground: Called each time the app moves to the background (iOS) or resigns
    ///     active (macOS), before `flush`.
    ///   - flush: Called after each `didEnterBackground`, without delaying later calls. On iOS it
    ///     runs inside an expiring activity so the system grants extra time to finish.
    static func observe(
        center: NotificationCenter = .default,
        didEnterForeground: @escaping @Sendable () -> Void,
        didEnterBackground: @escaping @Sendable () -> Void,
        flush: @escaping @Sendable () async -> Void
    ) -> Task<Void, Never>? {
        guard let foregroundNotification, let backgroundNotification else { return nil }

        let (transitions, continuation) = AsyncStream.makeStream(of: Transition.self)
        let observers = [
            center.addObserver(forName: foregroundNotification, object: nil, queue: nil) { _ in
                continuation.yield(.foreground)
            },
            center.addObserver(forName: backgroundNotification, object: nil, queue: nil) { _ in
                continuation.yield(.background)
            },
        ]

        return Task {
            defer {
                for observer in observers {
                    center.removeObserver(observer)
                }
            }
            await withDiscardingTaskGroup { group in
                var isInBackground = false
                for await transition in transitions {
                    switch transition {
                    case .foreground:
                        // Also skips a Mac app becoming active at launch, to match iOS.
                        guard isInBackground else { continue }
                        isInBackground = false
                        didEnterForeground()
                    case .background:
                        isInBackground = true
                        didEnterBackground()
                        group.addTask {
                            await withBackgroundTime(reason: "Davy flush", flush)
                        }
                    }
                }
            }
        }
    }
}

/// Runs `work`, on iOS asking the system to keep the process running in the background until it
/// finishes. Unlike `UIApplication.beginBackgroundTask`, this is available in app extensions.
///
/// If the system can't grant the time, or the time runs out, `work` still continues for as long
/// as the process does.
private func withBackgroundTime(reason: String, _ work: () async -> Void) async {
    #if os(iOS) || os(tvOS) || os(visionOS)
    let finished = DispatchSemaphore(value: 0)
    ProcessInfo.processInfo.performExpiringActivity(withReason: reason) { expired in
        // The activity lasts until this block returns, so wait for `work` while time is granted.
        // The system calls the block again with `expired` set when the time runs out, which
        // releases the wait. Extra signals are harmless.
        if expired {
            finished.signal()
        } else {
            finished.wait()
        }
    }
    await work()
    finished.signal()
    #else
    await work()
    #endif
}
