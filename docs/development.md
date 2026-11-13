# Development

Davy has a client for each platform, built to send exactly the same requests. This page covers how the repository is laid out and how to test changes.

## Repository layout

| Path | Contents |
| --- | --- |
| `Package.swift`, `Sources/`, `Tests/` | The Swift package for iOS and macOS. Swift Package Manager needs `Package.swift` at the root of the repository |
| `Example/` | The iOS example app |
| `Android/` | A Gradle project with the Kotlin library, `davy`, and the Android example app, `example` |
| `Spec/` | What both clients are tested against: the OpenAPI spec, `openapi.yaml`, and the shared request fixtures, `fixtures.json` |
| `docs/` | This documentation |

Neither library has runtime dependencies. Only their tests use other libraries: Yams in Swift, and SnakeYAML, org.json, and AndroidX Test in Kotlin. Apps using Davy never fetch them.

## Keeping the clients in step

Both clients' tests check what they send against `Spec/`:

- **The spec.** Every kind of event is sent through each client in every request format, and each request is checked against the spec's schemas and its written rules for query strings: parameter order and percent-encoding. The tests also check that each status code is handled as the spec describes.
- **The fixtures.** Each client must build the requests in `fixtures.json` exactly, byte for byte, which catches differences the spec allows, such as encoding and key order.

So a change to what Davy sends means changing the spec, the fixtures, and both clients together. Changes to how Davy behaves, such as delivery and retries, should also be made in both clients; each client's behavior tests mirror the other's.

## Running the tests

### Swift

From the root of the repository:

```sh
swift test
```

Or open `Package.swift` in Xcode and choose **Product > Test**.

### Kotlin

Open the `Android` folder in Android Studio, or use the command line with `JAVA_HOME` set to JDK 17 or later, such as the one bundled with Android Studio:

```sh
cd Android
# Unit tests, including the spec and fixtures.
./gradlew test
# Device tests, on a running emulator or connected device.
./gradlew connectedAndroidTest
```

The device tests check what only a real device can: the session data and diagnostics Davy reads from Android, sending over Android's HTTP stack, and the debug screen.

### The spec

To lint the OpenAPI document after changing it (requires Node.js):

```sh
npx @redocly/cli lint Spec/openapi.yaml
```

It reports two expected warnings: there's no license, and the server is `example.com`.
