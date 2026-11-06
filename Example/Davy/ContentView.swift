import SwiftUI
import Davy

struct ContentView: View {
    @Environment(\.analytics) private var telemetry

    var body: some View {
        NavigationStack {
            Form {
                SessionSection(sessionID: telemetry?.sessionID)
                LogEventsSection()
                FlushSection()
                DebugSection()
            }
            .formStyle(.grouped)
            .navigationTitle("Davy")
        }
        .logBreadcrumb("content_viewed")
    }
}

/// Shows the identifier Davy sends with every event logged during this launch.
struct SessionSection: View {
    let sessionID: UUID?

    var body: some View {
        Section {
            if let sessionID {
                Text(sessionID.uuidString)
                    .font(.footnote.monospaced())
                    .textSelection(.enabled)
            } else {
                Text("Analytics is disabled.")
                    .foregroundStyle(.secondary)
            }
        } header: {
            Text("Session ID")
        } footer: {
            Text("Generated each launch and never stored, so events can be linked within a session but not to a user or device.")
        }
    }
}

/// Logs breadcrumbs and errors in response to user actions.
struct LogEventsSection: View {
    @Environment(\.analytics) private var telemetry
    @State private var notificationsEnabled = false

    var body: some View {
        Section {
            Button("Log Breadcrumb") {
                telemetry?.log(.breadcrumb("button_tapped"))
            }
            Toggle("Notifications", isOn: $notificationsEnabled)
                .onChange(of: notificationsEnabled) { _, isEnabled in
                    telemetry?.log(.breadcrumb(isEnabled ? "notifications_enabled" : "notifications_disabled"))
                }
            Button("Log Error") {
                telemetry?.log(.error("network_unavailable"))
            }
        } header: {
            Text("Log Events")
        } footer: {
            Text("log(_:) returns immediately. Each event is written to disk, then sent in a batch once batchSize events are pending or after debounceInterval passes without a new one.")
        }
    }
}

/// Sends pending events without waiting for a full batch or the debounce interval.
struct FlushSection: View {
    @Environment(\.analytics) private var telemetry

    var body: some View {
        Section {
            Button("Send Pending Events Now") {
                telemetry?.flush()
            }
        } footer: {
            Text("Pending events are also sent when the app moves to the background and on the next launch. Failed sends are retried automatically.")
        }
    }
}

/// Presents Davy's debug view, which lists pending and sent events.
struct DebugSection: View {
    @State private var showsDebugEvents = false

    var body: some View {
        Section {
            Button("Debug Events") {
                showsDebugEvents = true
            }
            .davyDebugSheet(isPresented: $showsDebugEvents)
        } footer: {
            Text("Lists events waiting to be sent and events sent since launch, with everything each one is sent with.")
        }
    }
}

#Preview {
    ContentView()
}
