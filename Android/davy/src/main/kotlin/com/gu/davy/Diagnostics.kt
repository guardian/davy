package com.gu.davy

import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import kotlin.math.roundToInt

/**
 * The state of the app and device when an error was logged, added to the error's `info` under
 * standard keys to help explain it, for example by showing that an error only happens when the app
 * is low on memory or the device is overheating.
 *
 * The keys match the Swift client's, but the values come from Android and mean slightly different
 * things; see [capture]. Like [Session], values describe conditions, not the person using the app,
 * and are rounded to whole numbers. Nothing here identifies a device across sessions.
 */
internal class Diagnostics(context: Context) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager?
    private var baselineCpuMillis = Process.getElapsedCpuTime()
    private var baselineWallMillis = SystemClock.elapsedRealtime()

    /**
     * Captures the current state of the app and device as `info` entries, omitting any value that
     * can't be read:
     *
     * - `cpu_percent`: CPU time used by all the app's threads as a percentage of one core, so it can
     *   be over 100 on a device with several cores. Averaged since the previous error at least a
     *   second earlier, or since [Davy] was created.
     * - `memory_used_mb`: the app's Java and native heap use, in megabytes.
     * - `memory_available_mb`: how many more megabytes the Java heap can grow before the app runs
     *   out of memory.
     * - `thermal_state`: `none`, `light`, `moderate`, `severe`, `critical`, `emergency`, or
     *   `shutdown`, on Android 10 and later.
     * - `low_power_mode`: whether Battery Saver is on, `true` or `false`.
     */
    @Synchronized
    fun capture(): Map<String, String> {
        val runtime = Runtime.getRuntime()
        val javaHeapUsed = runtime.totalMemory() - runtime.freeMemory()
        val info = linkedMapOf<String, String>()
        cpuPercent()?.let { info[CPU_PERCENT] = it.toString() }
        info[MEMORY_USED_MB] = megabytes(javaHeapUsed + Debug.getNativeHeapAllocatedSize()).toString()
        info[MEMORY_AVAILABLE_MB] = megabytes(runtime.maxMemory() - javaHeapUsed).toString()
        thermalState()?.let { info[THERMAL_STATE] = it }
        powerManager?.let { info[LOW_POWER_MODE] = it.isPowerSaveMode.toString() }
        return info
    }

    private fun cpuPercent(): Int? {
        val cpuMillis = Process.getElapsedCpuTime()
        val wallMillis = SystemClock.elapsedRealtime()
        val elapsed = wallMillis - baselineWallMillis
        if (elapsed <= 0) return null
        val percent = ((cpuMillis - baselineCpuMillis) * 100.0 / elapsed).roundToInt().coerceAtLeast(0)
        if (elapsed >= 1000) {
            baselineCpuMillis = cpuMillis
            baselineWallMillis = wallMillis
        }
        return percent
    }

    private fun thermalState(): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return when (powerManager?.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "none"
            PowerManager.THERMAL_STATUS_LIGHT -> "light"
            PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
            PowerManager.THERMAL_STATUS_SEVERE -> "severe"
            PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
            else -> null
        }
    }

    private fun megabytes(bytes: Long): Int = (bytes / (1024.0 * 1024.0)).roundToInt()

    companion object {
        const val CPU_PERCENT = "cpu_percent"
        const val MEMORY_USED_MB = "memory_used_mb"
        const val MEMORY_AVAILABLE_MB = "memory_available_mb"
        const val THERMAL_STATE = "thermal_state"
        const val LOW_POWER_MODE = "low_power_mode"

        /** Every key [capture] can return. */
        val KEYS = setOf(CPU_PERCENT, MEMORY_USED_MB, MEMORY_AVAILABLE_MB, THERMAL_STATE, LOW_POWER_MODE)
    }
}
