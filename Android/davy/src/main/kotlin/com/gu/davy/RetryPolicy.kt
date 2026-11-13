package com.gu.davy

import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Controls how long to wait before sending again after a failure or a server-requested pause. */
internal data class RetryPolicy(
    val initialDelay: Duration = 5.seconds,
    val maxDelay: Duration = 300.seconds,
    /**
     * The longest `Retry-After` delay honored. Longer values are capped, so a misconfigured server
     * can't stop delivery indefinitely.
     */
    val maxRetryAfter: Duration = 24.hours,
    /**
     * The fraction of each backoff delay that is randomized, so devices that failed together don't
     * all retry at the same moment.
     */
    val jitter: Double = 0.5,
    /**
     * The most a `Retry-After` delay is randomly lengthened by, as a fraction of the delay. Retries
     * are never earlier than the server asked.
     */
    val retryAfterJitter: Double = 0.1,
) {
    /**
     * Exponential backoff: `initialDelay * 2^(failures - 1)`, capped at [maxDelay], then shortened
     * by a random amount of up to [jitter] of the delay.
     */
    fun delay(afterFailures: Int): Duration {
        val exponent = (afterFailures - 1).coerceIn(0, 16)
        val delay = minOf(initialDelay * (1 shl exponent), maxDelay)
        return delay * (1 - jitter * Random.nextDouble())
    }

    /**
     * The pause for a server's `Retry-After` value in seconds: at least 1 second, at most
     * [maxRetryAfter], then lengthened by a random amount of up to [retryAfterJitter].
     */
    fun pause(retryAfterSeconds: Double): Duration {
        val pause = retryAfterSeconds.coerceIn(1.0, maxRetryAfter.inWholeMilliseconds / 1000.0)
        return (pause * (1 + retryAfterJitter * Random.nextDouble())).seconds
    }
}
