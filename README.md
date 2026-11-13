<p align="center">
  <img src="Davy-Logo.png" alt="Davy: Dedicated App Vitals for You. A safety lamp for your app." width="240">
</p>

# Davy

**A safety lamp for your app.** Davy is a lightweight, privacy-minded telemetry client for iOS, macOS, and Android. You log small, anonymous events, and Davy stores them on the device and delivers them to an endpoint you run. Bring your own backend.

Like the miner's lamp it's named after, Davy is an early warning system. Use it to answer questions about your app as a whole:

- Is an error happening more often than usual?
- Has a key action, such as signing in, suddenly dropped or surged?
- Is the new build behaving like the previous one?

Davy is built for big-picture reporting: how often something happens, which errors are most common, and what led up to them. It isn't for product analytics, crash reporting, or looking into individual users or sessions.

## Features

- **Two simple kinds of event.** Breadcrumbs record that something happened, and errors record that something went wrong.
- **Context for errors.** Each error records the app's CPU and memory use, the device's thermal state, and whether power saving is on.
- **Cheap to call.** Logging returns immediately and is safe from any thread.
- **Reliable delivery.** Events are written to disk before they're sent, so they survive crashes and going offline, and failed requests are retried with backoff.
- **Your server stays in control.** It can pause every client with `Retry-After`, and remove duplicates by event ID.
- **Privacy-minded.** No user, device, or advertising identifiers, and a new random session ID every launch.
- **The same requests on every platform.** Both clients are tested against one [OpenAPI spec](Spec/openapi.yaml) and shared [fixtures](Spec/fixtures.json), so you can query events from every platform together.
- **A debug screen** that lists pending and sent events during development.
- **No dependencies.**

## Getting started

### 1. Add Davy to your app

| Platform | Requires | Setup |
| --- | --- | --- |
| iOS and macOS | iOS 18 or macOS 15, Xcode 27 | [Setting up on iOS and macOS](docs/setup-apple.md) |
| Android | Android 6.0 (API 23) | [Setting up on Android](docs/setup-android.md) |

You also need an endpoint of your own to receive events; see [Your backend](docs/backend.md).

### 2. Create one instance when your app launches

Keep it for the app's lifetime, and set `environment` to keep test data apart from real usage.

```swift
// Swift
import Davy

let telemetry = Davy(configuration: .init(
    endpoint: URL(string: "https://api.example.com/events")!,
    environment: "production"
))
```

```kotlin
// Kotlin
import com.gu.davy.Davy
import com.gu.davy.DavyConfiguration
import com.gu.davy.Event
import java.net.URI

val telemetry = Davy(context, DavyConfiguration(
    endpoint = URI("https://api.example.com/events"),
    environment = "production",
))
```

### 3. Log events

```swift
// Swift
telemetry.log(.breadcrumb("app_launched"))
telemetry.log(.breadcrumb("payment_selected", info: ["payment_method": "apple_pay"]))
telemetry.log(.error("payment_declined", info: ["reason": "card_expired"]))
```

```kotlin
// Kotlin
telemetry.log(Event.Breadcrumb("app_launched"))
telemetry.log(Event.Breadcrumb("payment_selected", mapOf("payment_method" to "google_pay")))
telemetry.log(Event.Error("payment_declined", mapOf("reason" to "card_expired")))
```

Davy sends events in the background, in batches. Log a breadcrumb such as `app_launched` at launch, so you can count sessions and turn raw counts into rates. Use short, stable names, the same on every platform, and **never include personal data** in names or `info`.

## Documentation

- [Setting up on iOS and macOS](docs/setup-apple.md): installation, SwiftUI, app extensions, and the debug view
- [Setting up on Android](docs/setup-android.md): installation, your `Application` class, and the debug screen
- [Logging events](docs/logging-events.md): breadcrumbs, errors, naming, timing, and sending events now
- [Configuration](docs/configuration.md): every option and its default
- [How delivery works](docs/delivery.md): when events are sent, what's guaranteed, and how retries work
- [Debugging events](docs/debugging.md): the debug screen and snapshots
- [Your backend](docs/backend.md): what your endpoint receives and how it should respond
- [Privacy](docs/privacy.md): what Davy collects, and what it doesn't
- [Development](docs/development.md): the repository, the shared spec, and running the tests
