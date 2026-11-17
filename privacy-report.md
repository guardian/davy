# Davy privacy review

- **Scope:** commit `461b607`: the Swift client (`Sources/Davy`), the Kotlin client (`Android/davy`), `Spec/`, `docs/`, `TestServer/`, and both example apps.
- **Method:** I read every source file on the data path (capture → disk → request → network) and compared what it does with `docs/privacy.md` and `Spec/openapi.yaml`. I also captured the exact headers sent by a `URLSession` with Davy's configuration on macOS.
- **Goal assessed:** keep measuring aggregate behaviour (rates, trends, funnels, and what leads up to errors) while making sure no event can be tied to a person or a device. That includes us, using the other data the Guardian holds.

> This is an engineering review, not legal advice. Involve the DPO for the legal basis (UK GDPR, PECR) and store disclosures.

---

## 1. Summary

The payload is well designed. Davy collects no identifiers, sends coarse session fields, generates a random session ID per instance, never sends cookies (on Apple platforms), never sends error descriptions, and is tested against a strict spec. **Most of the remaining risk sits around the payload rather than in it:** network metadata, how long sessions last, and free-form fields that rely on developers being careful.

| # | Finding | Severity |
| --- | --- | --- |
| F1 | The HTTP stack adds `User-Agent` and `Accept-Language` headers that aren't in the spec or the privacy docs | **High** |
| F2 | IP addresses and connection metadata reach the endpoint, and the only guidance is "avoid logging it" | **High** |
| F3 | A session lasts as long as the process, possibly days, and lifecycle events turn it into a usage timeline | **Medium–High** |
| F4 | Consecutive sessions can be linked when leftover events are sent on relaunch | Medium |
| F5 | Keeping personal data out depends entirely on developers; a docs example logs `push_id` | Medium |
| F6 | Fine-grained quasi-identifiers can single people out in small groups | Medium |
| F7 | Millisecond device timestamps allow clock-skew fingerprinting | Low–Medium |
| F8 | There's no runtime opt-out or way to delete pending events | Medium |
| F9 | On iOS, pending events are included in device backups (Android excludes them) | Low–Medium |
| F10 | Davy doesn't require HTTPS | Low |
| F11 | Configured headers and cookies can attach a user's identity | Low |
| F12 | The query string format puts all data into infrastructure logs | Low |
| F13 | Debug screens ship in release builds | Low |
| F14 | The test server exposes recorded events to the local network | Low (dev only) |
| F15 | No privacy manifest or app store disclosure guidance | Low (process) |

**Top six actions**, quickest wins first:

1. Set fixed `User-Agent` and `Accept-Language` headers on every request (F1).
2. Strip IP addresses at the edge, use a dedicated hostname, and derive country at the edge if it's needed (F2).
3. Start a new session after inactivity, and stop sending leftover events back to back with the new session's (F3, F4).
4. Validate `info`, sanitise `rejected_name`, and fix the `push_id` example (F5).
5. Require a minimum group size (k-anonymity) in dashboards, and coarsen timestamps and fields at ingest (F6, F7).
6. Add an `isEnabled` switch that deletes pending events, exclude iOS storage from backups, and ship a privacy manifest (F8, F9, F15).

---

## 2. What already works well (verified)

| Practice | Evidence |
| --- | --- |
| No user, device, or advertising IDs | `Sources/Davy/Session.swift`, `Android/.../Session.kt`. Neither reads the identifier for vendor, the advertising ID, the Android ID, the device name, the time zone, or the screen size |
| Locale trimmed to language and region | `Session.swift:73-78`, `Session.kt` `locale()` |
| Random session ID kept in memory only, never stored | `Davy.swift:67`, `Davy.kt:53` |
| No cookies or cache on Apple platforms | `EventTransport.swift:44-51` (ephemeral configuration, no cookie storage, `httpShouldSetCookies = false`) |
| Error descriptions never sent, only the type | `Davy.swift` `log(_:name:info:)`, `Davy.kt` `log(error, …)` |
| Diagnostics rounded, and no battery level or free disk space | `Diagnostics.swift`, `Diagnostics.kt` |
| Strict wire contract (`additionalProperties: false`) tested on both platforms | `Spec/openapi.yaml`, `SpecConformanceTests.swift`, `SpecConformanceTest.kt` |
| Android storage excluded from backups | `Davy.kt:222` (`noBackupFilesDir`) |
| Debug activity not exported | `Android/davy/src/main/AndroidManifest.xml:9` |
| Oversized events drop their `info` (often where free text is) | `EventPipeline.swift:214-240` |
| Apple OS log messages redact dynamic strings by default | `os.Logger` interpolation, for example `Davy.swift:166` |
| No third-party runtime dependencies | `Package.swift`, `Android/davy/build.gradle.kts` (test dependencies only) |

---

## 3. Threat model: who could re-identify someone?

| Party | Sees | Current mitigation |
| --- | --- | --- |
| **A. Network observer** | IP, SNI, timing, sizes | HTTPS, if the endpoint uses it (not enforced; see F10) |
| **B. The endpoint operator and analysts** | IP, headers, exact timing, payload; **can join with other Guardian logs** (sign-in, API, CDN) | Docs advise not logging IPs |
| **C. Third-party infrastructure** (CDN, WAF, log pipelines) | IP, headers, full URL (query string format) | None specific |
| **D. The device** | Files on disk, backups, Logcat, debug screen | Android: no backup. iOS: none |

**What anonymity requires:** under UK GDPR (Recital 26, and the ICO's anonymisation guidance), data is anonymous only if no one can identify individuals by "means reasonably likely to be used", **including the controller combining it with other data it holds**. IP addresses are generally personal data (CJEU *Breyer*, C-582/14). So **raw Davy data at the endpoint should currently be treated as pseudonymous personal data** until IPs and other linkable metadata are removed. Party B is the main risk.

---

## 4. Findings

### F1. The HTTP stack adds headers that help identify a device (High)

**Evidence.** Davy's `URLSession` (`EventTransport.swift:44-51`) sets neither header, and `RequestBuilder` adds only the configured headers (`RequestBuilder.swift:53`). A request made with the same session configuration sent:

```
User-Agent: <executable name>/<CFBundleVersion> CFNetwork/3860.700.1 Darwin/25.6.0
Accept-Language: en-GB,en;q=0.9
Accept-Encoding: gzip, deflate
```

On Android, `HttpURLConnection` sends the system `http.agent` by default, typically `Dalvik/2.1.0 (Linux; U; Android 15; Pixel 9 Build/AP3A.241005.015)`. That includes the **firmware build ID**, which is more detailed than `osVersion`. I didn't confirm this on a device during this review.

**Why it matters.** `Accept-Language` is the user's **ranked list of preferred languages**, which is far more distinctive than the `locale` that Davy deliberately trims. It contradicts `privacy.md:10` ("Davy only sends the events you log, their session data…"). CDNs and load balancers log `User-Agent` by default.

**Recommendation.**

- In both `RequestBuilder`s, set `User-Agent: Davy` and `Accept-Language: *`, applied after `configuration.headers` so they can't be overridden. **I verified that `URLSession` sends both overrides as set.**
- Add both headers to `Spec/openapi.yaml` and `Spec/fixtures.json`. This is a wire change, so the spec, fixtures, and both clients have to change together.
- Add a test at the transport level, not just `RequestBuilder` (for example, extend `HttpUrlConnectionTransportTest.kt`), so headers the system adds are caught.

### F2. IP addresses and connection metadata reach the endpoint (High)

**Evidence.** `privacy.md:12` and `backend.md:17` only say to avoid logging IPs.

**Why it matters.** With an IP address and a timestamp, anyone who can see other Guardian logs (sign-in, API, CDN) can link a Davy session to an account. Load balancers, CDNs, and WAFs usually log IPs **before** any application code runs, so "don't log it" is easy to get wrong.

**Recommendation (in tiers).**

1. **Now:**
   - Use a **dedicated hostname** for Davy that isn't shared with authenticated APIs, with separate logs and separate access control.
   - **Strip or zero the IP at the edge**, before any log is written.
   - If abuse protection needs the IP, rate-limit in memory, or use a keyed hash with a daily salt that's then discarded.
   - **If you want geography, derive the country at the edge, add it as a field, and drop the IP.** That keeps aggregate geography without the IP.
2. **Later:** **Oblivious HTTP** (RFC 9458) through an independent relay. The relay sees the IP but not the content, and our gateway sees the content but not the IP. CryptoKit provides HPKE on Apple platforms. Android would need a library, which conflicts with the no-runtime-dependency rule, so **make `EventTransport` public and pluggable** so apps can provide an OHTTP transport without Davy taking on a dependency.

### F3. A session lasts as long as the process, which can be days (Medium–High)

**Evidence.** The session is created once, when the instance is created (`Davy.swift:67`, `Davy.kt:53`), and never renewed. On both platforms, a backgrounded process can live for hours or days, and a Notification Service Extension process is reused across notifications. With `logsAppLifecycleEvents`, every foreground and background transition is recorded with a millisecond timestamp. `privacy.md:8` says "a new session ID every launch", which is accurate but suggests sessions are short.

**Why it matters.** A session that lasts several days is a detailed behavioural profile (when and how often someone reads). The longer a session runs, the more likely its pattern is unique and can be matched with other data.

**Recommendation.**

- Start a new session when the app returns to the foreground after **30 minutes or more in the background**, and after a **maximum duration** (for example, 12 to 24 hours).
- This fits the existing design, since each event is already stored with its session (`PendingEvent`), and `nextDelivery()` already splits requests by session (`EventPipeline.swift:335-350`).
- Funnels and "what led up to an error" almost always happen within minutes, so the analytics are unaffected.
- Update `privacy.md` to describe how long sessions actually last.

### F4. Consecutive sessions can be linked on relaunch (Medium)

**Evidence.** On launch, events left over from the previous session are sent straight away (`EventPipeline.swift:145-148`), oldest first, followed by the new session's events in the same send, one session per request, over the same shared connection (`URLSession.davy`).

**Why it matters.** Back-to-back requests on one connection let the server chain session N to session N+1. Leftover events are most likely after crashes, which are exactly the sessions you most want to analyse. Once IPs are gone (F2), this becomes the main remaining link between sessions.

**Recommendation.** Send leftover sessions after a random delay (for example, 1 to 10 minutes), and avoid reusing a connection across sessions: a separate `URLSession` per session on Apple platforms, and `Connection: close` on the last request of a session on Android.

### F5. Keeping personal data out depends entirely on developers (Medium)

**Evidence.**

- Names and `info` are free-form strings. Nothing checks `info` keys or values, and `onInvalidEventName` defaults to `.send` (`DavyConfiguration.swift:101`, `DavyConfiguration.kt:52`).
- `.sendError` and the too-large replacement **send the original name** as `rejected_name` (`Davy.swift:169`, `Davy.kt:210`, `EventPipeline.swift:222`), so a name containing personal data is sent anyway.
- **The docs example logs `push_id`** (`docs/setup-apple.md:93-94`, `Davy.swift:189`). If that's a per-recipient message ID rather than a campaign ID, it can be joined with the push provider's logs, which map it to a device token and so to a person.
- Android logs event names to Logcat in release builds (`EventPipeline.kt:223,226`, `Davy.kt:207`).

**Recommendation.**

- **Change the example** to a value many users share, for example `["push_type": "breaking_news"]`, and say that `info` values must be shared by many users.
- Add **`onInvalidInfo`**, mirroring `onInvalidEventName`:
  - Keys must be lower snake case.
  - Values are limited to a safe vocabulary: lower snake case words, numbers, and `true` or `false`, with a maximum length (for example, 64) and a maximum number of keys (for example, 10).
  - When reporting a rejection, send **the key only, never the value**.
- Add optional **personal data detection** for names and values: email addresses, phone numbers, UUIDs, long runs of digits, URLs, IP addresses, and JWT or base64 tokens.
- **Sanitise `rejected_name`:** truncate it and replace runs of digits and email addresses with placeholders.
- Add an optional **event registry**: one JSON file, shared by both apps, listing the allowed names, keys, and values. It would crash in debug builds, drop events in release builds, and double as the data inventory for DPIAs.
- On Android, don't log event names in release builds.

### F6. Fine-grained quasi-identifiers can single people out in small groups (Medium)

**Evidence.**

- Session fields include the OS version down to the patch, the exact hardware model or `Build.MODEL`, the locale with region, the build, and the environment.
- Timestamps have millisecond precision.
- Diagnostics are exact integers. On Android, `memory_available_mb` reflects the device's heap limit.

**Why it matters.** Each field is coarse on its own, but combined they can narrow a group to one person: for example `environment=beta`, `iPad13,4`, `17.2.1`, and `cy_GB`. This is most likely for staff and beta builds, rare Android models, and minority locales.

**Recommendation.**

- **Server side first**, with no app release needed:
  - Require a **minimum group size** (for example, 20 to 50 sessions) for every dashboard or query cell.
  - Group rare models under "other".
  - Store `osVersion` as `major.minor` in analytics tables.
  - **Treat non-production environments as personal data**, since they're mostly staff.
- **Client option** (a wire change): precision settings for the OS version, device model (exact, family, or omitted), and locale (language and region, language, or region), plus banded diagnostics (memory in 64 MB steps, CPU in 10% steps).

### F7. Millisecond device timestamps allow clock-skew fingerprinting (Low–Medium)

**Evidence.** `sentAt` and `timestamp` have millisecond precision, and `backend.md` ("Fixing clock differences") advises storing `sentAt` and `receivedAt` with each event.

**Why it matters.** `receivedAt − sentAt` estimates how far the device's clock is off. On a device whose clock was set manually, or has drifted, that offset is stable across sessions, so it can link them. Precise timestamps also make it easy to match events with other logs.

**Recommendation.** Apply the clock correction at ingest, store only the corrected timestamp **truncated to the second** (or the minute in analytics tables), and **discard the raw `sentAt` and `receivedAt`**. Update `backend.md`.

### F8. No runtime opt-out or way to delete pending events (Medium)

**Evidence.** Neither client has an enable, disable, or delete API. The only option is not creating an instance, and events already pending are still sent by the next instance.

**Why it matters.** Users who opt out expect nothing more to be sent, including events already queued. Depending on the legal basis, PECR may require consent or an opt-out for storing data on the device and sending it.

**Recommendation.**

- Add `isEnabled = false` / `setEnabled(false)`, which stops logging, cancels sends, and deletes pending events, the pause file, and the debug history. Re-enabling starts a new session.
- Add a static `deleteAllData(storageDirectory:)` that can be called without an instance.
- Document how to connect it to the consent platform.

### F9. On iOS, pending events are included in device backups (Low–Medium)

**Evidence.** The default iOS storage directory is in Application Support (`DavyConfiguration.swift:127`), which iCloud and computer backups include unless it's excluded. Android deliberately uses `noBackupFilesDir` (`Davy.kt:222`, `setup-android.md:64`).

**Recommendation.** Set `isExcludedFromBackup` on the storage directory when it's created (in `FileEventStore.init`), and set file protection explicitly to `.completeUntilFirstUserAuthentication`, which still allows sending in the background. Document that both platforms behave the same.

### F10. Davy doesn't require HTTPS (Low)

**Evidence.** Any URL scheme is accepted for the endpoint. Davy relies on App Transport Security and Android's cleartext defaults, which apps with broad exceptions (common for apps that show web content) turn off.

**Recommendation.** Reject endpoints that aren't `https`, except for loopback addresses, and log a clear error.

### F11. Configured headers and cookies can attach a user's identity (Low)

**Evidence.** `configuration.md:37,55` show `Authorization: Bearer <token>` without saying the token must be the same for the whole app. On Android, cookies depend on the global `CookieHandler` (`EventTransport.kt:42`, `privacy.md:9`).

**Recommendation.**

- In the docs, require an **app-wide key**, never a user or session token.
- Reject a `Cookie` header in `headers`.
- On Android, warn at startup if `CookieHandler.getDefault() != null`.
- A dedicated hostname (F2) also prevents cookies shared with another domain from being sent.

### F12. The query string format puts all data into infrastructure logs (Low)

**Why it matters.** CDNs, load balancers, WAFs, and APM tools record URLs, often with longer retention and broader access than the event store, and alongside the IP address and `User-Agent`.

**Recommendation.** Prefer the JSON formats. If the query string format is needed, treat that log pipeline as the event store: strip IPs, don't log `User-Agent`, and limit retention. Note this in the docs.

### F13. Debug screens ship in release builds (Low)

**Evidence.** `DavyDebugActivity` is declared in the library's manifest, and `DavyDebugView` is public. Both show the session ID and headers, including `Authorization`, and offer Share.

**Recommendation.** Advise only showing them in internal builds, set `FLAG_SECURE` on the Android activity, and consider a separate debug artifact.

### F14. The test server exposes recorded events to the local network (Low, dev only)

**Evidence.** It listens on `0.0.0.0` by default (`TestServer/src/main.ts:7`), with an unauthenticated `GET /_/requests` over plain HTTP.

**Recommendation.** Listen on `127.0.0.1` by default, and require `HOST=0.0.0.0` to test from a device.

### F15. No privacy manifest or app store disclosure guidance (Low, process)

**Evidence.** The package has no `PrivacyInfo.xcprivacy`, and the docs don't cover App Store privacy labels or Google Play's Data safety section.

**Recommendation.**

- Ship a privacy manifest declaring:
  - `NSPrivacyTracking` false, with no tracking domains.
  - Collected data types: Product Interaction, Performance Data, and Other Diagnostic Data, each not linked to the user and not used for tracking, for Analytics and App Functionality.
- Confirm that no required-reason APIs are used.
- Add a "Store disclosures" section to `privacy.md`.

---

## 5. Keeping aggregate insight with stronger anonymity

| Question | How to answer it after these changes |
| --- | --- |
| Error rate by build or OS | Event counts ÷ `app_launched` sessions, unaffected |
| Funnel conversion | Sequences within a session, unaffected by starting a new session after 30 minutes of inactivity |
| What led up to an error | Within the session, or attach the last N breadcrumb names to each error |
| Device and OS breakdowns | Coarsened fields with a minimum group size |
| Geography | Country derived at the edge (F2) |
| Performance (`duration_ms`) | Percentiles, or banded values |

**More advanced options:**

- **Breadcrumb trail on errors:** this makes an optional **mode without session IDs** possible, where every request stands alone. You can still count sessions with `app_launched`, and get context from the trail.
- **Aggregation on the device:** for high-volume breadcrumbs, send hourly or daily counts per name instead of individual events, which removes event-level timing altogether.
- **Differential privacy:** add calibrated noise to published counts at the query layer. This is the simplest form. At Guardian scale the noise is negligible for top-level metrics. Local differential privacy (randomized response) would only be needed for sensitive yes/no signals.
- **Random send delays**, so sending times can't be matched with other traffic.

---

## 6. Proposed API (illustrative)

```swift
DavyConfiguration(
    endpoint: URL(string: "https://telemetry.example.com/events")!,
    privacy: .init(
        sessionRotation: .afterBackground(.minutes(30), maxDuration: .hours(24)),
        osVersionPrecision: .minor,      // "26.1"
        deviceModel: .exact,             // or .family, .omitted
        locale: .language,               // "en"
        timestampPrecision: .seconds,
        onInvalidInfo: .sendError,       // reports the key, never the value
        detectsPersonalData: true
    )
)
telemetry.isEnabled = false              // stops logging and deletes pending events
```

Anything that changes requests has to update `Spec/openapi.yaml`, `Spec/fixtures.json`, and both clients together.

---

## 7. Roadmap

**Now (docs and small changes, days)**

- F1: fixed `User-Agent` and `Accept-Language` headers, with the spec, fixtures, and tests updated.
- Fix the `push_id` example, and correct the docs (§8).
- Backend guidance:
  - Strip IPs at the edge, and derive the country there if needed.
  - Use a dedicated hostname.
  - Truncate timestamps, and discard `sentAt` and `receivedAt`.
  - Require a minimum group size.
  - **Retention:** keep raw events for a short time (for example, 30 to 90 days) and aggregates for longer.
- Clarify the `Bearer <token>` guidance, and make the test server listen on `127.0.0.1` by default.

**Next (client features)**

- New sessions after inactivity, and delayed sending of leftover events.
- `onInvalidInfo`, personal data detection, and `rejected_name` sanitising.
- `isEnabled` and deleting pending events.
- iOS backup exclusion, an HTTPS requirement, and the privacy manifest.

**Later (strategic)**

- A public `EventTransport` and Oblivious HTTP.
- The shared event registry.
- Breadcrumb trails on errors, a mode without session IDs, and aggregation on the device.
- Differential privacy at the query layer.

---

## 8. Doc corrections

- `docs/privacy.md:8`: "every launch" → "each instance; can last for days unless sessions are renewed" (until F3 ships).
- `docs/privacy.md:10`: mention `User-Agent` and `Accept-Language` until F1 ships.
- `docs/backend.md`, "Fixing clock differences": advise discarding raw `sentAt` and `receivedAt` after correcting timestamps.
- `docs/configuration.md:37,55`: "an app-wide key, never a user's token".
- `docs/setup-apple.md:93-94` and `Sources/Davy/Davy.swift:189`: replace `push_id` with a campaign-level value.
