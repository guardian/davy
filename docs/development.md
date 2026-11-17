# Development

Davy has a client for each platform, built to send exactly the same requests. This page covers how the repository is laid out and how to test changes.

## Repository layout

| Path | Contents |
| --- | --- |
| `Package.swift`, `Sources/`, `Tests/` | The Swift package for iOS and macOS. Swift Package Manager needs `Package.swift` at the root of the repository |
| `Example/` | The iOS example app |
| `Android/` | A Gradle project with the Kotlin library, `davy`, and the Android example app, `example` |
| `Spec/` | What both clients are tested against: the OpenAPI spec, `openapi.yaml`, and the shared request fixtures, `fixtures.json` |
| `TestServer/` | A local server to send events to while developing; see [Trying the clients against a server](#trying-the-clients-against-a-server) |
| `docs/` | This documentation |

Neither library has runtime dependencies. Only their tests use other libraries: Yams in Swift, and SnakeYAML, org.json, and AndroidX Test in Kotlin. Apps using Davy never fetch them.

## Keeping the clients in step

Both clients' tests check what they send against `Spec/`:

- **The spec.** Every kind of event is sent through each client, and each request is checked against the spec's schemas. The tests also check that each status code is handled as the spec describes.
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

### The test server

The test server is written in TypeScript and runs on Node.js 24 or later, which runs TypeScript directly. From the `TestServer` folder:

```sh
npm install
# The tests, then a type check.
npm test
npm run typecheck
```

The tests check that the server accepts every request in `fixtures.json` without problems, and reports requests that break the spec.

## Trying the clients against a server

`TestServer/` is a small server you can point an app at while developing. It checks each request against `Spec/openapi.yaml`, and lists the events it received on a web page. It keeps everything in memory and has no authentication, so use it only for development.

Start it with Node.js 24 or later:

```sh
cd TestServer
npm install
npm start
```

Or with Docker, which needs a running Docker engine, such as Docker Desktop or Colima. With the Compose plugin:

```sh
cd TestServer
docker compose up --build
```

Without it, from the root of the repository:

```sh
docker build -t davy-test-server TestServer
docker run --rm -p 8080:8080 -v "$PWD/Spec:/app/Spec:ro" davy-test-server
```

The container reads the repository's `Spec` folder, so after changing the spec, restart the server rather than rebuilding it.

Then open http://localhost:8080 and set the app's `endpoint` to the server. The example apps' debug builds already use it.

| App running on | Endpoint |
| --- | --- |
| Mac or iOS Simulator | `http://localhost:8080/events` |
| iPhone or iPad on the same network | `http://<your Mac's IP address>:8080/events` |
| Android emulator or device | `http://localhost:8080/events`, after running `adb reverse tcp:8080 tcp:8080` |

Any path works, as long as it doesn't start with `/_/`, which the server uses for its own page.

The server uses plain HTTP, which platforms restrict:

- **Android** blocks plain HTTP, so allow it to `localhost` in debug builds with a network security config, as the example app does in `src/debug`. Use `adb reverse` rather than the emulator's `10.0.2.2` or your computer's IP address, since on Android 17, apps that target API 37 need the `ACCESS_LOCAL_NETWORK` permission to connect to local network addresses.
- **macOS** apps in the App Sandbox need the **Outgoing Connections (Client)** capability, as the example app has.
- **iOS** allows plain HTTP to `localhost` in Simulator. From a device, iOS asks for permission to access the local network, and if App Transport Security blocks the requests, add `NSAllowsLocalNetworking` to the app's `NSAppTransportSecurity` settings.

The page shows each event as it arrives, newest first, with:

- **Problems.** Every way its request breaks the spec. Select an event to see the problems and the raw request.
- **Duplicates.** Events with an ID the server has already stored, which happens when a client retries after a response was lost.
- **The response.** Use **Respond with** to change how the server responds to every request, to see how the clients handle rejection (400), throttling and pausing (429 and 503 with `Retry-After`), and failures (500).

To read what the server received from a script, use `GET /_/requests`, or **Download JSON** on the page.
