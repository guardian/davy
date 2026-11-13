# Privacy

Davy is designed so its data describes how an app is behaving, not the people using it. It works the same way on every platform.

- **No identifiers.** Davy doesn't collect user or account IDs, device or advertising identifiers (such as the Android ID or identifier for vendor), device names, time zones, screen sizes, or custom locale settings.
- **Coarse session data.** Each session records only the environment, app version and build, OS and OS version, device model, and locale. The locale is language and region only, such as `en_GB`. See [Session fields](backend.md#session-fields).
- **Conditions, not people, on errors.** Errors' diagnostics describe the app's resource usage and the device's conditions at that moment, rounded to whole numbers. Davy doesn't collect battery level or free disk space, which can help identify a device. See [Diagnostics](backend.md#diagnostics).
- **A new session ID every launch.** Each launch gets a random session ID that's never stored or reused, so events can be linked within a session but not across sessions or to a device.
- **No cookies.** Requests never send cookies, so they can't be linked to each other that way. On Android, this relies on your app not installing a default `java.net.CookieHandler`.
- **Only your endpoint.** Davy only sends the events you log, their session data, and diagnostics for errors, and only to your endpoint.

Reports stay anonymous only if your event names and `info` contain no personal data, so don't include IDs, email addresses, free text, or error descriptions. When logging a platform error, Davy sends only its type, not its description. Your server still sees each request's IP address, so avoid logging it if you want to keep reports anonymous.
