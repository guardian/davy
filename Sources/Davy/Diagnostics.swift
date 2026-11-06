import Foundation
import os

/// The state of the app and device when an error was logged, added to the error's `info` under
/// standard keys to help explain it, for example by showing that an error only happens when the
/// app is low on memory or the device is overheating.
///
/// Like ``Session``, values describe conditions, not the person using the app, and are rounded to
/// whole numbers. Nothing here identifies a device across sessions.
enum Diagnostics {
    /// The app's CPU usage as a percentage of one core, summed across its threads, so it can be
    /// over 100 on a device with several cores. The system averages each thread's usage over the
    /// last few seconds.
    static let cpuPercentKey = "cpu_percent"
    /// The app's memory footprint in megabytes: the figure Xcode's memory gauge shows, and that
    /// the system compares with the app's memory limit.
    static let memoryUsedKey = "memory_used_mb"
    /// How many more megabytes the app can use before reaching its memory limit, at which point
    /// the system terminates it. Omitted where there's no limit, such as on macOS or in Simulator.
    static let memoryAvailableKey = "memory_available_mb"
    /// The device's thermal state: `"nominal"`, `"fair"`, `"serious"`, or `"critical"`.
    static let thermalStateKey = "thermal_state"
    /// Whether Low Power Mode is on: `"true"` or `"false"`.
    static let lowPowerModeKey = "low_power_mode"

    /// Every key ``capture()`` can return.
    static let keys: Set<String> = [cpuPercentKey, memoryUsedKey, memoryAvailableKey, thermalStateKey, lowPowerModeKey]

    private static let bytesPerMegabyte = 1024.0 * 1024.0

    /// Captures the current state of the app and device as `info` entries, omitting any value
    /// that can't be read. Takes a few microseconds per thread in the app, so it's only done for
    /// errors.
    static func capture() -> [String: String] {
        let processInfo = ProcessInfo.processInfo
        let values: [String: String?] = [
            cpuPercentKey: cpuPercent.map(String.init),
            memoryUsedKey: memoryFootprint.map(megabytes).map(String.init),
            memoryAvailableKey: memoryAvailable.map(megabytes).map(String.init),
            thermalStateKey: thermalState(processInfo.thermalState),
            lowPowerModeKey: String(processInfo.isLowPowerModeEnabled),
        ]
        return values.compactMapValues { $0 }
    }

    private static func megabytes(_ bytes: UInt64) -> Int {
        Int((Double(bytes) / bytesPerMegabyte).rounded())
    }

    /// The sum of the CPU usage of every thread that isn't idle, as reported by the scheduler.
    private static var cpuPercent: Int? {
        let task = mach_task_self_
        var threads: thread_act_array_t?
        var threadCount: mach_msg_type_number_t = 0
        guard task_threads(task, &threads, &threadCount) == KERN_SUCCESS, let threads else { return nil }
        // `task_threads` returns a send right for each thread and allocates the array in this
        // task, so both have to be released.
        defer {
            for index in 0..<Int(threadCount) {
                mach_port_deallocate(task, threads[index])
            }
            vm_deallocate(
                task,
                vm_address_t(UInt(bitPattern: threads)),
                vm_size_t(Int(threadCount) * MemoryLayout<thread_t>.stride)
            )
        }

        var usage = 0.0
        for index in 0..<Int(threadCount) {
            var info = thread_basic_info()
            var count = mach_msg_type_number_t(MemoryLayout<thread_basic_info>.size / MemoryLayout<natural_t>.size)
            let result = withUnsafeMutablePointer(to: &info) {
                $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                    thread_info(threads[index], thread_flavor_t(THREAD_BASIC_INFO), $0, &count)
                }
            }
            guard result == KERN_SUCCESS, info.flags & TH_FLAGS_IDLE == 0 else { continue }
            usage += Double(info.cpu_usage) / Double(TH_USAGE_SCALE)
        }
        return Int((usage * 100).rounded())
    }

    /// The app's physical footprint in bytes.
    private static var memoryFootprint: UInt64? {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let result = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        guard result == KERN_SUCCESS else { return nil }
        return info.phys_footprint
    }

    /// The bytes left before the app reaches its memory limit, or `nil` if it has none.
    private static var memoryAvailable: UInt64? {
        #if os(iOS) || os(tvOS) || os(watchOS) || os(visionOS)
        let available = os_proc_available_memory()
        return available > 0 ? UInt64(available) : nil
        #else
        nil
        #endif
    }

    private static func thermalState(_ state: ProcessInfo.ThermalState) -> String? {
        switch state {
        case .nominal: "nominal"
        case .fair: "fair"
        case .serious: "serious"
        case .critical: "critical"
        @unknown default: nil
        }
    }
}
