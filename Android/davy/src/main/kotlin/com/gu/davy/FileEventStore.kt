package com.gu.davy

import android.util.Log
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.UUID

/** An encoded event read back from disk. */
internal class StoredEvent(val fileName: String, val loggedAtMillis: Long, val data: ByteArray)

/**
 * Persists each event as its own file so writes are atomic and survive crashes.
 *
 * Files are named `<loggedAtMillis>-<sequence>-<uuid>.json`, so sorting by name restores logging
 * order after a relaunch, and the logged time can be read without opening the file.
 *
 * Not thread-safe: use it from one thread at a time.
 */
internal class FileEventStore(private val directory: File) {
    /** Pending file names, oldest first. */
    private val fileNames: MutableList<String>
    private var sequence = 0L

    init {
        if (!directory.isDirectory && !directory.mkdirs()) {
            Log.e(TAG, "Failed to create event directory $directory")
        }
        val contents = directory.list().orEmpty()
        // Files left by a write that didn't finish.
        contents.filter { it.endsWith(TEMPORARY_EXTENSION) }.forEach { File(directory, it).delete() }
        fileNames = contents.filter { it.endsWith(EXTENSION) }.sorted().toMutableList()
    }

    val count: Int get() = fileNames.size

    @Throws(IOException::class)
    fun append(data: ByteArray, loggedAtMillis: Long) {
        val name = String.format(Locale.ROOT, "%015d-%010d-%s%s", loggedAtMillis, sequence, UUID.randomUUID().toString().uppercase(Locale.ROOT), EXTENSION)
        sequence += 1
        // Write to a temporary file, then rename it, so a crash never leaves a partial event.
        val temporary = File(directory, name + TEMPORARY_EXTENSION)
        temporary.writeBytes(data)
        if (!temporary.renameTo(File(directory, name))) {
            temporary.delete()
            throw IOException("Failed to move $temporary into place")
        }
        fileNames += name
    }

    /** Reads up to [limit] of the oldest events, skipping any in [excluded]. Unreadable files are discarded. */
    fun oldest(limit: Int, excluded: Set<String> = emptySet()): List<StoredEvent> {
        val events = mutableListOf<StoredEvent>()
        val unreadable = mutableListOf<String>()
        for (name in fileNames) {
            if (events.size >= limit) break
            if (name in excluded) continue
            val data = try {
                File(directory, name).readBytes()
            } catch (_: IOException) {
                null
            }
            if (data == null || data.isEmpty()) {
                unreadable += name
            } else {
                events += StoredEvent(name, loggedAt(name) ?: Long.MIN_VALUE, data)
            }
        }
        if (unreadable.isNotEmpty()) {
            Log.e(TAG, "Discarding ${unreadable.size} unreadable event file(s)")
            remove(unreadable)
        }
        return events
    }

    fun remove(events: List<StoredEvent>) {
        remove(events.map { it.fileName })
    }

    /** Deletes events logged before [cutoffMillis]. */
    fun prune(cutoffMillis: Long) {
        val expired = fileNames.filter { (loggedAt(it) ?: Long.MIN_VALUE) < cutoffMillis }
        if (expired.isEmpty()) return
        Log.i(TAG, "Pruning ${expired.size} expired event(s)")
        remove(expired)
    }

    private fun remove(names: Collection<String>) {
        val removed = names.toSet()
        removed.forEach { File(directory, it).delete() }
        fileNames.removeAll { it in removed }
    }

    private companion object {
        const val TAG = "Davy"
        const val EXTENSION = ".json"
        const val TEMPORARY_EXTENSION = ".tmp"

        fun loggedAt(fileName: String): Long? = fileName.substringBefore('-').toLongOrNull()
    }
}
