package com.gu.davy

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.io.Closeable

/** Reports when the app moves between the foreground and background. */
internal fun interface LifecycleSource {
    /** Calls the handlers on each transition until the returned [Closeable] is closed. */
    fun observe(onForeground: () -> Unit, onBackground: () -> Unit): Closeable
}

/**
 * Tells when the whole app moves between the foreground and background from its activities
 * starting and stopping. Launching isn't reported, so [onForeground] is only called after
 * [onBackground]. An activity being recreated, such as on rotation, isn't a transition.
 *
 * Call it from the main thread, where activity callbacks run.
 */
internal class LifecycleTracker(private val onForeground: () -> Unit, private val onBackground: () -> Unit) {
    private var startedActivities = 0
    private var isInBackground = false

    fun activityStarted() {
        startedActivities += 1
        if (startedActivities == 1 && isInBackground) {
            isInBackground = false
            onForeground()
        }
    }

    fun activityStopped(isChangingConfigurations: Boolean) {
        // Never below zero, in case activities had already started when tracking began.
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0 && !isChangingConfigurations && !isInBackground) {
            isInBackground = true
            onBackground()
        }
    }
}

/** Observes the app's activities with [Application.ActivityLifecycleCallbacks]. */
internal class ActivityLifecycleSource(private val application: Application) : LifecycleSource {
    override fun observe(onForeground: () -> Unit, onBackground: () -> Unit): Closeable {
        val tracker = LifecycleTracker(onForeground, onBackground)
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = tracker.activityStarted()

            override fun onActivityStopped(activity: Activity) = tracker.activityStopped(activity.isChangingConfigurations)

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityResumed(activity: Activity) = Unit

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        application.registerActivityLifecycleCallbacks(callbacks)
        return Closeable { application.unregisterActivityLifecycleCallbacks(callbacks) }
    }
}
