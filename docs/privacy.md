# Privacy

Davy is designed so its data describes how an app is behaving, not the people using it. It works the same way on every platform.

- **No identifiers.** Davy doesn't collect user or account IDs, device or advertising identifiers (such as the Android ID or identifier for vendor), device names, time zones, screen sizes, or custom locale settings.
- **Coarse session data.** Each session records only the environment, app version and build, OS and OS version, device model, locale, and any session `info` you configure. The locale is language and region only, such as `en_GB`. See [Session fields](backend.md#session-fields).
- **Conditions, not people, on errors.** Errors' diagnostics describe the app's resource usage and the device's conditions at that moment, rounded to whole numbers. Davy doesn't collect battery level or free disk space, which can help identify a device. See [Diagnostics](backend.md#diagnostics).
- **A new session ID every launch.** Each launch gets a random session ID that's never stored or reused, so events can be linked within a session but not across sessions or to a device.
- **No cookies.** Davy doesn't use or store cookies from the app or the server, and ignores configured `Cookie` and `Cookie2` headers. It always sends `User-Agent: Davy` and `Accept-Language: *` instead of the system defaults.
- **Only your endpoint.** Davy requires HTTPS unless you explicitly enable plain HTTP for development, never follows redirects, and never forwards queued events to a different configured URL.

Reports stay anonymous only if your event names and `info` (both on events and on the session) contain no personal data, so don't include IDs, email addresses, free text, or error descriptions. When logging a platform error, Davy sends only its type, not its description. Your server still sees each request's IP address, so avoid logging it if you want to keep reports anonymous.
