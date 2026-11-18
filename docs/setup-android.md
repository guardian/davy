# Setting up on Android

The Kotlin library supports Android 6.0 (API 23) or later. Building it needs JDK 17 or later, such as the one bundled with Android Studio.

## Installation

Davy isn't published to a Maven repository yet. Until it is, include it in your build from a checkout of this repository, as a [composite build](https://docs.gradle.org/current/userguide/composite_builds.html). In your `settings.gradle.kts`:

```kotlin
includeBuild("path/to/davy/Android")
```

Then depend on it from your app module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.gu:davy")
}
```

Davy's manifest adds the `INTERNET` permission to your app.

## Creating the instance

Create one instance when your app starts and keep it for the app's lifetime. The usual place is your `Application` subclass:

```kotlin
import android.app.Application
import com.gu.davy.Davy
import com.gu.davy.DavyConfiguration
import com.gu.davy.Event
import java.net.URI

class MyApplication : Application() {
    lateinit var telemetry: Davy
        private set

    override fun onCreate() {
        super.onCreate()
        telemetry = Davy(this, DavyConfiguration(
            endpoint = URI("https://api.example.com/events"),
            environment = if (BuildConfig.DEBUG) "debug" else "production",
        ))
        telemetry.log(Event.Breadcrumb("app_launched"))
    }
}
```

Register it in your manifest with `<application android:name=".MyApplication" …>`, then reach it from an activity with `(application as MyApplication).telemetry`, or provide it through your dependency injection. `log` and `flush` can be called from any thread.

## Showing the debug screen

Start `DavyDebugActivity` from a debug menu:

```kotlin
DavyDebugActivity.start(context, telemetry)
```

Or use `DavyDebugActivity.intent(context, telemetry)` to start it some other way, such as from a notification. It's built from Android's own widgets, so it adds no dependencies. See [Debugging events](debugging.md) for what it shows.

## How it behaves on Android

- **Backgrounding.** The app is in the background once none of its activities are started; an activity being recreated, such as on rotation, doesn't count. Pending events are sent then. Android can suspend the app soon after, so this is best effort, and anything not sent is sent on the next launch.
- **Storage.** Pending events are stored in `davy` in the app's no-backup files directory, from `Davy.defaultStorageDirectory(context)`, so they're never backed up.
- **Coroutines.** `flushAndWait()` and `debugSnapshot()` are `suspend` functions, and the block passed to `measure` can suspend when called from a coroutine. Davy itself doesn't depend on kotlinx.coroutines.
- **Stopping.** `close()` stops an instance, for example at the end of a test. Its pending events stay on disk.
- **Invalid names.** With `onInvalidEventName = InvalidEventNameAction.CRASH`, `log` throws an `IllegalArgumentException`.
- **Exceptions.** `log(exception, name)` records the exception's class name under `error_type`. In builds minified with R8, your own exception classes are renamed unless you keep their names.
- **Cookies.** Davy uses a separate OkHttp client with cookies disabled. It does not use a process-wide `java.net.CookieHandler`.
- **Session data.** `appVersion` and `build` are the app's `versionName` and `versionCode`. `os` is `Android`, `osVersion` is padded to three parts, such as `15.0.0`, and `deviceModel` is the model name, such as `Pixel 9`.
- **Diagnostics.** Errors' diagnostics use the same keys as on Apple platforms with Android's values. See [Diagnostics](backend.md#diagnostics).

## Example app

`Android/example` is an app that owns one instance in its `Application` subclass, logs events from buttons, sends them when it moves to the background, and opens the debug screen from **Debug Events**. Its configuration sets every option, with a comment explaining each one.

To run it, open the `Android` folder in Android Studio, choose the **example** run configuration and a device, and click **Run**. Or, from the command line, run `./gradlew :example:installDebug` in the `Android` folder.

Debug builds send events to the [test server](development.md#trying-the-clients-against-a-server) at `http://localhost:8080/events`. To see events arrive, start the server. The emulator or device reaches it at `localhost` through `adb reverse tcp:8080 tcp:8080`, which Android forgets whenever the emulator restarts or the device reconnects, so every debug build runs it on each connected emulator and device for you. If you connect one after building, run it yourself. Debug builds allow plain HTTP to `localhost` only. Without the server, requests fail and events stay pending, which shows how failures and retries appear in the debug screen. Watch them in Logcat with the `Davy` tag. Release builds use a placeholder endpoint; replace it with your own.
