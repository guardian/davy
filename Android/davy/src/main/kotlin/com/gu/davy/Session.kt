package com.gu.davy

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale
import java.util.UUID

/**
 * Common data about the app and device, captured once when a session starts and sent as the
 * request's `session` object.
 *
 * Fields are deliberately coarse: they describe the kind of device and app build an event came
 * from, not the person using it. Nothing here is persistent or unique to a device, such as an
 * Android ID, advertising ID, device name, time zone, or screen size.
 */
internal data class Session(
    /** A random identifier, generated per session and never reused. */
    val id: UUID,
    val environment: String?,
    /** The app's `versionName`, for example `1.2.0`. */
    val appVersion: String?,
    /** The app's `versionCode`, for example `45`. */
    val build: String?,
    val os: String,
    /** The Android version as `major.minor.patch`, for example `15.0.0`. */
    val osVersion: String,
    /** The device's model, for example `Pixel 9`. */
    val deviceModel: String?,
    /** The device's language and region, for example `en_GB`. */
    val locale: String?,
    /** [DavyConfiguration.info] and any entries added with [Davy.addSessionInfo], omitted when empty. */
    val info: Map<String, Any>? = null,
) {
    /**
     * Returns a copy of this session with [additionalInfo] merged into [info], replacing any
     * existing values for the same keys.
     */
    fun addingInfo(additionalInfo: Map<String, Any>): Session =
        copy(info = (info.orEmpty() + additionalInfo).takeIf { it.isNotEmpty() })

    companion object {
        const val OS = "Android"

        /** Captures the current app and device data for a new session. */
        fun start(context: Context, environment: String?, info: Map<String, Any> = emptyMap()): Session {
            val packageInfo = packageInfo(context)
            return Session(
                id = UUID.randomUUID(),
                environment = environment,
                appVersion = packageInfo?.versionName,
                build = packageInfo?.let(::versionCode),
                os = OS,
                osVersion = osVersion(Build.VERSION.RELEASE),
                deviceModel = Build.MODEL?.takeIf { it.isNotBlank() },
                locale = locale(Locale.getDefault()),
                info = info.takeIf { it.isNotEmpty() }?.let(InfoValues::normalize),
            )
        }

        /**
         * [release] as `major.minor.patch`, so every platform sends the same format: `15` becomes
         * `15.0.0`. `0.0.0` if it doesn't start with a number, as on some preview builds.
         */
        fun osVersion(release: String?): String {
            val parts = release.orEmpty().split('.').map { it.toIntOrNull() }.takeWhile { it != null }.take(3)
            if (parts.isEmpty()) return "0.0.0"
            return (parts + List(3 - parts.size) { 0 }).joinToString(".")
        }

        /**
         * The language and region of [locale] only, for example `en_GB`, or just the language if
         * there's no region, so locale customizations aren't sent. Old language codes are replaced
         * with current ones, such as `iw` with `he`, to match other platforms.
         */
        fun locale(locale: Locale): String? {
            val language = when (val code = locale.language) {
                "iw" -> "he"
                "in" -> "id"
                "ji" -> "yi"
                else -> code
            }.takeIf { it.isNotEmpty() } ?: return null
            val region = locale.country.takeIf { it.isNotEmpty() } ?: return language
            return "${language}_$region"
        }

        private fun packageInfo(context: Context): PackageInfo? = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

        private fun versionCode(packageInfo: PackageInfo): String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toString()
            }
    }
}
