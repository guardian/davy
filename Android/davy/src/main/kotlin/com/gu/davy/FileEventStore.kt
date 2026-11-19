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

    /** How many events were discarded without being sent because they expired, couldn't be read, or couldn't be decoded. */
    var discardedCount: Int = 0
        private set

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

    /** When each pending event was logged, oldest first, read from the file names. */
    val loggedTimes: List<Long> get() = fileNames.mapNotNull(::loggedAt)

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

    /**
     * Reads up to [limit] of the oldest events.
     *
     * Empty files, which can never be sent, are discarded. Files that exist but can't be read right
     * now are kept and skipped, so they're read again next time. They expire after `maxEventAge` if
     * they never become readable.
     */
    fun oldest(limit: Int): List<StoredEvent> {
        val events = mutableListOf<StoredEvent>()
        val broken = mutableListOf<String>()
        var unreadableCount = 0
        for (name in fileNames) {
            if (events.size >= limit) break
            val file = File(directory, name)
            val data = try {
                file.readBytes()
            } catch (_: IOException) {
                null
            }
            when {
                data == null && file.exists() -> unreadableCount += 1
                data == null || data.isEmpty() -> broken += name
                else -> events += StoredEvent(name, loggedAt(name) ?: Long.MIN_VALUE, data)
            }
        }
        if (unreadableCount > 0) {
            Log.e(TAG, "Couldn't read $unreadableCount event file(s); keeping them to try again later")
        }
        if (broken.isNotEmpty()) {
            Log.e(TAG, "Discarding ${broken.size} empty or missing event file(s)")
            discard(broken)
        }
        return events
    }

    /** Deletes events the server responded to. */
    fun remove(events: List<StoredEvent>) {
        remove(events.map { it.fileName })
    }

    /** Deletes events that can't be sent, such as ones that can't be decoded. */
    fun discard(events: List<StoredEvent>) {
        discard(events.map { it.fileName })
    }

    /** Deletes events logged before [cutoffMillis]. */
    fun prune(cutoffMillis: Long) {
        val expired = fileNames.filter { (loggedAt(it) ?: Long.MIN_VALUE) < cutoffMillis }
        if (expired.isEmpty()) return
        Log.i(TAG, "Pruning ${expired.size} expired event(s)")
        discard(expired)
    }

    /** Deletes every event. */
    fun discardAll() {
        discard(fileNames.toList())
    }

    /**
     * Deletes the oldest events until at most [limit] remain, not counting [excluding], such as
     * events in a request in flight. Returns how many were deleted.
     */
    fun discardOldest(limit: Int, excluding: Set<String>): Int {
        val candidates = fileNames.filter { it !in excluding }
        val excess = candidates.size - limit
        if (excess <= 0) return 0
        val count = fileNames.size
        discard(candidates.take(excess))
        return count - fileNames.size
    }

    private fun discard(names: Collection<String>) {
        val count = fileNames.size
        remove(names)
        discardedCount += count - fileNames.size
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
