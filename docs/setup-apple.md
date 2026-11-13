# Setting up on iOS and macOS

The Swift package supports iOS 18 or later and macOS 15 or later, including app extensions. It needs Xcode 27 or later (Swift 6.4).

## Installation

Add Davy with Swift Package Manager.

In Xcode, choose **File > Add Package Dependencies…**, enter `https://github.com/guardian/davy`, and add the **Davy** library to your app target and to any app extension targets that log events.

Or, in `Package.swift`:

```swift
dependencies: [
    .package(url: "https://github.com/guardian/davy.git", branch: "main"),
],
targets: [
    .target(
        name: "MyApp",
        dependencies: [
            .product(name: "Davy", package: "davy"),
        ]
    ),
]
```

## Creating the instance

Create one instance when your app launches and keep it for the app's lifetime. In a SwiftUI app, create it in your `App` and pass it to your views through the environment with `\.telemetry`:

```swift
import Davy
import SwiftUI

@main
struct MyApp: App {
    private let telemetry = Davy(configuration: .init(
        endpoint: URL(string: "https://api.example.com/events")!
    ))

    init() {
        telemetry.log(.breadcrumb("app_launched"))
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(\.telemetry, telemetry)
        }
    }
}
```

`Davy` is `Sendable`, so you can share the instance across threads, tasks, and actors. Outside SwiftUI, keep it in your app's dependency container.

## Using it in SwiftUI views

Read the instance from the environment. It's `nil` by default, so previews and views outside your app's hierarchy never log or send events:

```swift
struct CheckoutView: View {
    @Environment(\.telemetry) private var telemetry

    var body: some View {
        Button("Pay") {
            telemetry?.log(.breadcrumb("pay_tapped"))
        }
        .logBreadcrumb("checkout_viewed")
    }
}
```

`logBreadcrumb(_:)` logs a breadcrumb each time the view appears, so it can be logged more than once, for example when navigating back to the view.

## App extensions

Davy can also be used in app extensions, for example to record when a push notification arrives in a Notification Service Extension. Extensions aren't flushed automatically and can be suspended as soon as they finish, so call `flushAndWait()` first:

```swift
import Davy
import UserNotifications

final class NotificationService: UNNotificationServiceExtension {
    // One instance per process, which the system may reuse for several notifications.
    static let telemetry = Davy(configuration: .init(
        endpoint: URL(string: "https://api.example.com/events")!
    ))

    override func didReceive(
        _ request: UNNotificationRequest,
        withContentHandler contentHandler: @escaping (UNNotificationContent) -> Void
    ) {
        let pushID = request.content.userInfo["push_id"] as? String ?? "unknown"
        Self.telemetry.log(.breadcrumb("push_received", info: ["push_id": pushID]))
        Task {
            await Self.telemetry.flushAndWait()
            contentHandler(request.content)
        }
    }
}
```

- An extension has its own container, so the default `storageDirectory` is already separate from the app's.
- Events that aren't delivered before the extension is suspended stay on disk and are sent the next time it runs.
- `flushAndWait()` can take as long as the network request, so keep handling `serviceExtensionTimeWillExpire()`.
- A Notification Service Extension only runs for notifications sent with `mutable-content: 1`.

## Showing the debug view

Present `DavyDebugView` from a debug menu. It shows the instance set in the environment with `\.telemetry`:

```swift
@State private var showsDebugEvents = false

Button("Debug Events") {
    showsDebugEvents = true
}
.davyDebugSheet(isPresented: $showsDebugEvents)
```

Or present `DavyDebugView(telemetry: telemetry)` yourself. It has its own `NavigationStack`, so present it rather than pushing it. See [Debugging events](debugging.md) for what it shows.

## How it behaves on Apple platforms

- **Backgrounding.** Pending events are sent when the app moves to the background (iOS) or resigns active (macOS). On iOS, the system is asked for extra time to finish sending. App extensions aren't flushed automatically.
- **Storage.** Pending events are stored in `Application Support/<bundle identifier>/Davy`, from `DavyConfiguration.defaultStorageDirectory`.
- **Invalid names.** With `onInvalidEventName: .crash`, `log(_:)` stops the app with `fatalError`.
- **Environment.** TestFlight and App Store builds share a binary, so telling them apart for `environment` needs a runtime check or a separate build configuration.
- **Session data.** `appVersion` and `build` are the main bundle's `CFBundleShortVersionString` and `CFBundleVersion`. `os` is `iOS`, `macOS`, `macCatalyst`, `visionOS`, `tvOS`, or `watchOS`, and `deviceModel` is the hardware model identifier, such as `iPhone17,1`, or `Simulator`.
- **Diagnostics.** Errors' `thermal_state` is `nominal`, `fair`, `serious`, or `critical`, and `low_power_mode` is whether Low Power Mode is on. See [Diagnostics](backend.md#diagnostics).

## Example app

`Example/Davy Example.xcodeproj` is a SwiftUI app that owns one instance, passes it through the environment, logs events from the UI, and presents the debug view. Its configuration sets every option, with a comment explaining each one.

Its endpoint is a placeholder, so requests fail and events stay pending, which shows how failures and retries appear in the debug view. Replace it with your own endpoint to see events arrive.
