import Charts
import SwiftUI

/// Lists the events a ``Davy`` instance is waiting to send, the events it has recently sent, and
/// the requests it sent them in, so you can check your events during development.
///
/// The overview shows the current session ID, how many events were logged since launch and how
/// many of those are pending and sent, and a chart of how many events were logged and sent each
/// minute over the last hour, with dotted lines where each session's first event was logged. The
/// events list shows pending and sent events together, most recently logged first, with an icon
/// showing whether each has been sent. The requests list shows each request, newest first, with
/// how the server responded, including requests that failed.
///
/// Select an event to see everything it's sent with, including its JSON and the requests it was
/// in. Select a request to see its events, headers, body, and response. The toolbar sends pending
/// events now and clears the sent history and requests. If sending fails, the reason is shown at
/// the top of each page.
///
/// The view has its own `NavigationStack`, so present it rather than pushing it, for example
/// from a debug menu with ``SwiftUICore/View/davyDebugSheet(isPresented:)``:
///
/// ```swift
/// Button("Debug Events") { showsDebugEvents = true }
///     .davyDebugSheet(isPresented: $showsDebugEvents)
/// ```
///
/// Events and requests are listed only if they were sent since the instance was created, up to
/// ``DavyConfiguration/debugHistoryLimit``. The view is a development tool, so its text is
/// in English and isn't localized.
public struct DavyDebugView: View {
    @Environment(\.telemetry) private var environmentTelemetry
    @Environment(\.dismiss) private var dismiss
    @Environment(\.isPresented) private var isPresented
    private let telemetry: Davy?

    /// Creates a view showing the events of the ``Davy`` instance set in the environment
    /// with `\.telemetry`.
    public init() {
        telemetry = nil
    }

    /// Creates a view showing the events of `telemetry`.
    public init(telemetry: Davy) {
        self.telemetry = telemetry
    }

    public var body: some View {
        NavigationStack {
            Group {
                if let telemetry = telemetry ?? environmentTelemetry {
                    DebugEventsScreen(telemetry: telemetry)
                } else {
                    ContentUnavailableView {
                        Label {
                            Text(verbatim: "No Davy Instance")
                        } icon: {
                            Image(systemName: "tray")
                        }
                    } description: {
                        Text(verbatim: "Set one in the environment with .environment(\\.telemetry, telemetry), or pass one to DavyDebugView(telemetry:).")
                    }
                }
            }
            .navigationTitle(Text(verbatim: "Events"))
            #if os(iOS)
            .navigationBarTitleDisplayMode(.inline)
            #endif
            .toolbar {
                if isPresented {
                    ToolbarItem(placement: .confirmationAction) {
                        Button {
                            dismiss()
                        } label: {
                            Text(verbatim: "Done")
                        }
                    }
                }
            }
        }
    }
}

/// What ``DavyDebugView`` shows.
private enum DebugPage: Hashable {
    case overview
    case events
    case requests
}

/// Where an event is in its journey to the server.
enum DebugEventStatus: Equatable {
    case pending
    case sent(at: Date, outcome: DavyDebugSnapshot.SentEvent.Outcome)
    /// Neither pending nor in the sent history, for example because it expired or the history
    /// was cleared.
    case unknown
}

/// An event in the list, pending or sent.
struct DebugEventItem: Identifiable {
    let event: DavyDebugSnapshot.LoggedEvent
    let status: DebugEventStatus

    var id: UUID { event.id }

    /// Pending and sent events together, most recently logged first.
    static func all(in snapshot: DavyDebugSnapshot) -> [DebugEventItem] {
        let sent = snapshot.sent.map { sentEvent in
            DebugEventItem(event: sentEvent.event, status: .sent(at: sentEvent.sentAt, outcome: sentEvent.outcome))
        }
        let sentIDs = Set(sent.map(\.id))
        let pending = snapshot.pending
            .filter { !sentIDs.contains($0.id) }
            .map { DebugEventItem(event: $0, status: .pending) }
        // Both lists are oldest first, so the later of two events logged at the same time comes
        // first too.
        return (sent + pending).enumerated()
            .sorted { first, second in
                guard first.element.event.timestamp != second.element.event.timestamp else {
                    return first.offset > second.offset
                }
                return first.element.event.timestamp > second.element.event.timestamp
            }
            .map(\.element)
    }

    /// Whether the event's name, an `info` or `sessionInfo` key or value, or its ID contains
    /// `searchText`, ignoring case and surrounding spaces. Empty text matches every event.
    func matches(searchText: String) -> Bool {
        let text = searchText.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return true }
        let fields = [event.name, event.id.uuidString]
            + event.info.flatMap { [$0.key, $0.value] }
            + event.sessionInfo.flatMap { [$0.key, $0.value] }
        return fields.contains { $0.localizedCaseInsensitiveContains(text) }
    }
}

/// Which events the events list shows.
enum DebugEventFilter: String, CaseIterable, Identifiable {
    case all
    case pending
    case sent
    case rejected
    case errors

    var id: Self { self }

    var title: String {
        switch self {
        case .all: "All"
        case .pending: "Pending"
        case .sent: "Sent"
        case .rejected: "Rejected"
        case .errors: "Errors"
        }
    }

    /// Why no events are listed when this filter and `searchText` match none.
    func noMatchesDescription(searchText: String) -> String {
        let isSearching = !searchText.trimmingCharacters(in: .whitespaces).isEmpty
        switch (isSearching, self) {
        case (true, .all): return "No events match the search."
        case (true, _): return "No events match the search and filter."
        default: return "No events match the filter."
        }
    }

    func includes(_ item: DebugEventItem) -> Bool {
        switch (self, item.status) {
        case (.all, _), (.pending, .pending), (.sent, .sent(_, .delivered)), (.rejected, .sent(_, .rejected)):
            true
        case (.errors, _):
            item.event.isError
        default:
            false
        }
    }
}

extension DavyDebugSnapshot.Request {
    /// Whether the body contains `searchText`, ignoring case and surrounding spaces. Empty text
    /// matches every request.
    func matches(searchText: String) -> Bool {
        let text = searchText.trimmingCharacters(in: .whitespaces)
        guard !text.isEmpty else { return true }
        return body.localizedCaseInsensitiveContains(text)
    }

    /// How many events the request carried, for example `1 event` or `3 events`.
    var eventCountTitle: String {
        eventIDs.count == 1 ? "1 event" : "\(eventIDs.count) events"
    }

    /// `HTTP 200`, or `No response` if none was received.
    var statusTitle: String {
        statusCode.map { "HTTP \($0)" } ?? "No response"
    }

    /// The body as indented JSON with sorted keys, or as sent if it isn't JSON.
    var prettyBody: String {
        guard let object = try? JSONSerialization.jsonObject(with: Data(body.utf8)),
              let data = try? JSONSerialization.data(withJSONObject: object, options: [.prettyPrinted, .sortedKeys, .withoutEscapingSlashes])
        else {
            return body
        }
        return String(decoding: data, as: UTF8.self)
    }
}

/// The latest ``DavyDebugSnapshot`` of an instance.
@MainActor
@Observable
private final class DebugEventsModel {
    /// Pending and sent events together, most recently logged first.
    private(set) var events: [DebugEventItem] = []
    private(set) var loggedCount = 0
    private(set) var pendingCount = 0
    private(set) var sentCount = 0
    private(set) var sentHistoryCount = 0
    /// Requests that finished, newest first.
    private(set) var requests: [DavyDebugSnapshot.Request] = []
    private(set) var lastFailure: DavyDebugSnapshot.Failure?
    private(set) var activity: [DavyDebugSnapshot.Activity] = []
    /// When each session's first event was logged, oldest first.
    private(set) var sessionStarts: [Date] = []
    private(set) var isLoaded = false

    /// Keeps the model up to date until the calling task is cancelled.
    func observe(_ telemetry: Davy) async {
        for await snapshot in telemetry.debugUpdates() {
            events = DebugEventItem.all(in: snapshot)
            loggedCount = snapshot.loggedCount
            pendingCount = snapshot.pending.count
            sentCount = snapshot.sentCount
            sentHistoryCount = snapshot.sent.count
            requests = snapshot.requests.reversed()
            lastFailure = snapshot.lastFailure
            activity = snapshot.activity
            sessionStarts = snapshot.sessionStarts.map(\.date)
            isLoaded = true
        }
    }

    func status(of id: UUID) -> DebugEventStatus {
        item(for: id)?.status ?? .unknown
    }

    /// The event with `id`, if it's still pending or in the sent history.
    func item(for id: UUID) -> DebugEventItem? {
        events.first { $0.id == id }
    }

    /// The requests that included the event with `id`, newest first.
    func requests(containing id: UUID) -> [DavyDebugSnapshot.Request] {
        requests.filter { $0.eventIDs.contains(id) }
    }
}

private struct DebugEventsScreen: View {
    let telemetry: Davy
    @State private var model = DebugEventsModel()
    @State private var selection = DebugPage.overview
    // Kept here so they last while switching between pages.
    @State private var searchText = ""
    @State private var filter = DebugEventFilter.all

    var body: some View {
        Group {
            switch selection {
            case .overview:
                DebugOverview(model: model, sessionID: telemetry.sessionID)
            case .events:
                DebugEventsList(
                    model: model,
                    sentHistoryLimit: telemetry.configuration.debugHistoryLimit,
                    searchText: searchText,
                    filter: $filter
                )
            case .requests:
                DebugRequestsList(
                    model: model,
                    historyLimit: telemetry.configuration.debugHistoryLimit,
                    searchText: searchText
                )
            }
        }
        // On every page, so the layout doesn't move when switching between them.
        .searchable(
            text: $searchText,
            prompt: Text(verbatim: selection == .requests ? "Body" : "Name, info, or ID")
        )
        .onChange(of: searchText) { _, text in
            if !text.isEmpty && selection == .overview {
                selection = .events
            }
        }
        .safeAreaInset(edge: .top, spacing: 0) {
            DebugEventsHeader(selection: $selection, eventCount: model.events.count, requestCount: model.requests.count)
        }
        .navigationDestination(for: DavyDebugSnapshot.LoggedEvent.self) { event in
            DebugEventDestination(event: event, model: model)
        }
        .navigationDestination(for: DavyDebugSnapshot.Request.self) { request in
            DebugRequestDestination(request: request, model: model)
        }
        .navigationDestination(for: DavyDebugSnapshot.Failure.self) { failure in
            DebugFailureDetail(failure: failure)
        }
        .toolbar {
            ToolbarItemGroup(placement: .debugActions) {
                Button(role: .destructive) {
                    telemetry.clearDebugHistory()
                } label: {
                    Text(verbatim: "Clear Sent History")
                }
                .disabled(model.sentHistoryCount == 0 && model.requests.isEmpty)
                Button {
                    telemetry.flush()
                } label: {
                    Text(verbatim: "Flush Now")
                }
            }
        }
        .task(id: ObjectIdentifier(telemetry)) {
            await model.observe(telemetry)
        }
    }
}

private struct DebugEventsHeader: View {
    @Binding var selection: DebugPage
    let eventCount: Int
    let requestCount: Int

    var body: some View {
        Picker(selection: $selection) {
            Text(verbatim: "Overview")
                .tag(DebugPage.overview)
            Text(verbatim: "Events (\(eventCount))")
                .tag(DebugPage.events)
            Text(verbatim: "Requests (\(requestCount))")
                .tag(DebugPage.requests)
        } label: {
            Text(verbatim: "Events")
        }
        .pickerStyle(.segmented)
        .labelsHidden()
        .padding()
        .background(.bar)
    }
}

/// Top-line numbers and a chart of the last hour's activity, which moves along each minute.
private struct DebugOverview: View {
    let model: DebugEventsModel
    let sessionID: UUID

    var body: some View {
        TimelineView(.everyMinute) { context in
            DebugOverviewForm(model: model, sessionID: sessionID, date: context.date)
        }
        .overlay {
            if !model.isLoaded {
                ProgressView()
            }
        }
    }
}

private struct DebugOverviewForm: View {
    let model: DebugEventsModel
    let sessionID: UUID
    /// When the hour of activity shown ends.
    let date: Date

    var body: some View {
        let activity = DebugActivityWindow(activity: model.activity, sessionStarts: model.sessionStarts, endingAt: date)
        Form {
            DebugSummarySection(
                sessionID: sessionID,
                loggedCount: model.loggedCount,
                pendingCount: model.pendingCount,
                sentCount: model.sentCount
            )
            if let lastFailure = model.lastFailure {
                DebugFailureSection(failure: lastFailure)
            }
            DebugActivitySection(
                minutes: activity.minutes,
                domain: activity.domain,
                sessionStartMinutes: activity.sessionStartMinutes,
                countAxisValues: activity.countAxisValues,
                timeAxisValues: activity.timeAxisValues,
                timeLabelValues: activity.timeLabelValues
            )
        }
        .formStyle(.grouped)
    }
}

private struct DebugSummarySection: View {
    let sessionID: UUID
    let loggedCount: Int
    let pendingCount: Int
    let sentCount: Int

    var body: some View {
        Section {
            DebugField("Session ID") {
                Text(sessionID.uuidString)
                    .font(.caption.monospaced())
            }
            .textSelection(.enabled)
            HStack(alignment: .top) {
                DebugStat("Logged", value: loggedCount, color: DebugActivitySeries.logged.color)
                DebugStat("Pending", value: pendingCount)
                DebugStat("Sent", value: sentCount, color: DebugActivitySeries.sent.color)
            }
        } header: {
            Text(verbatim: "Summary")
        } footer: {
            Text(verbatim: "Since launch, including events left over from earlier launches. Every logged event is pending or sent.")
        }
    }
}

/// A headline number, with the color of its line in the activity chart if it has one.
private struct DebugStat: View {
    let title: String
    let value: Int
    let color: Color?

    init(_ title: String, value: Int, color: Color? = nil) {
        self.title = title
        self.value = value
        self.color = color
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value, format: .number)
                .font(.title2.weight(.semibold))
                .monospacedDigit()
            HStack(spacing: 4) {
                if let color {
                    Circle()
                        .fill(color)
                        .frame(width: 8, height: 8)
                }
                Text(verbatim: title)
            }
            .font(.caption)
            .foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

/// A line in the activity chart.
private enum DebugActivitySeries: CaseIterable {
    case logged
    case sent

    var title: String {
        switch self {
        case .logged: "Logged"
        case .sent: "Sent"
        }
    }

    var color: Color {
        switch self {
        case .logged: .blue
        case .sent: .green
        }
    }

    /// Sent is dashed so both lines show where they overlap, as they do when every event logged
    /// in a minute is also sent in it.
    var lineStyle: StrokeStyle {
        switch self {
        case .logged: StrokeStyle(lineWidth: 2)
        case .sent: StrokeStyle(lineWidth: 2, dash: [5, 3])
        }
    }

    func count(in activity: DavyDebugSnapshot.Activity) -> Int {
        switch self {
        case .logged: activity.logged
        case .sent: activity.sent
        }
    }
}

/// The activity in each minute of the hour ending at a date, ready to chart.
private struct DebugActivityWindow {
    /// Every minute of the hour, oldest first, including quiet minutes, so the lines drop to zero
    /// rather than joining across them.
    let minutes: [DavyDebugSnapshot.Activity]
    let domain: ClosedRange<Date>
    /// Whole numbers from zero to just above the busiest minute, about four apart.
    let countAxisValues: [Int]
    /// The quarter hours in the hour.
    let timeAxisValues: [Date]
    /// The quarter hours far enough from the end for a label to fit after them.
    let timeLabelValues: [Date]
    /// The minutes in the hour in which a session's first event was logged.
    let sessionStartMinutes: [Date]

    init(activity: [DavyDebugSnapshot.Activity], sessionStarts: [Date], endingAt date: Date) {
        let start = DavyDebugSnapshot.activityStart(at: date)
        let end = DavyDebugSnapshot.Activity.minute(containing: date)
        let recorded = Dictionary(activity.map { ($0.minute, $0) }) { first, _ in first }
        let minuteCount = Int((end.timeIntervalSince(start) / 60).rounded())
        minutes = (0...minuteCount).map { offset in
            let minute = start.addingTimeInterval(TimeInterval(offset * 60))
            return recorded[minute] ?? DavyDebugSnapshot.Activity(minute: minute, logged: 0, sent: 0)
        }
        domain = start...end

        let busiest = minutes.map { max($0.logged, $0.sent) }.max() ?? 0
        let step = Self.axisStep(forMaximum: busiest)
        let top = max(4, (busiest + step - 1) / step * step)
        countAxisValues = Array(stride(from: 0, through: top, by: step))

        let calendar = Calendar.current
        timeAxisValues = minutes.map(\.minute).filter { calendar.component(.minute, from: $0) % 15 == 0 }
        timeLabelValues = timeAxisValues.filter { end.timeIntervalSince($0) >= 10 * 60 }

        // Placed at the start of their minutes, like the activity in them. Sessions that started
        // in the same minute share a line.
        sessionStartMinutes = Set(sessionStarts.map(DavyDebugSnapshot.Activity.minute(containing:)))
            .filter { (start...end).contains($0) }
            .sorted()
    }

    /// The smallest of 1, 2, 5, 10, 20, 50, … that reaches `maximum` in four steps.
    private static func axisStep(forMaximum maximum: Int) -> Int {
        var magnitude = 1
        while true {
            for multiplier in [1, 2, 5] where multiplier * magnitude * 4 >= maximum {
                return multiplier * magnitude
            }
            magnitude *= 10
        }
    }
}

private struct DebugActivitySection: View {
    let minutes: [DavyDebugSnapshot.Activity]
    let domain: ClosedRange<Date>
    let sessionStartMinutes: [Date]
    let countAxisValues: [Int]
    let timeAxisValues: [Date]
    let timeLabelValues: [Date]

    /// The legend entry for session start lines, which are labeled there rather than one by one
    /// so labels of sessions that started close together don't overlap.
    private static let sessionStartTitle = "Session start"

    var body: some View {
        Section {
            Chart {
                ForEach(sessionStartMinutes, id: \.self) { minute in
                    RuleMark(x: .value(Text(verbatim: "Session Start"), minute))
                        .foregroundStyle(by: .value(Text(verbatim: "Series"), Self.sessionStartTitle))
                        .lineStyle(StrokeStyle(lineWidth: 1.5, lineCap: .round, dash: [0, 4]))
                }
                ForEach(DebugActivitySeries.allCases, id: \.self) { series in
                    ForEach(minutes) { minute in
                        LineMark(
                            x: .value(Text(verbatim: "Minute"), minute.minute),
                            y: .value(Text(verbatim: "Events"), series.count(in: minute))
                        )
                        .foregroundStyle(by: .value(Text(verbatim: "Series"), series.title))
                        .lineStyle(series.lineStyle)
                        .interpolationMethod(.monotone)
                    }
                }
            }
            .chartForegroundStyleScale(
                domain: DebugActivitySeries.allCases.map(\.title) + [Self.sessionStartTitle],
                range: DebugActivitySeries.allCases.map(\.color) + [Color.gray]
            )
            .chartXScale(domain: domain)
            .chartXAxis {
                AxisMarks(values: timeAxisValues) { _ in
                    AxisGridLine()
                    AxisTick()
                }
                AxisMarks(values: timeLabelValues) { _ in
                    AxisValueLabel(format: .dateTime.hour().minute())
                }
            }
            .chartYScale(domain: 0...(countAxisValues.last ?? 1))
            .chartYAxis {
                AxisMarks(values: countAxisValues)
            }
            .frame(height: 200)
            .padding(.vertical, 8)
        } header: {
            Text(verbatim: "Last Hour")
        } footer: {
            Text(verbatim: "Events logged, and events the server accepted or rejected, each minute. Dotted lines mark when each session's first event was logged.")
        }
    }
}

/// Explains why events are still pending, with the full reason, which can be long for network
/// errors, a tap away.
private struct DebugFailureSection: View {
    let failure: DavyDebugSnapshot.Failure

    var body: some View {
        Section {
            NavigationLink(value: failure) {
                Label {
                    Text(failure.reason)
                        .font(.caption.monospaced())
                        .lineLimit(3)
                } icon: {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                }
            }
        } header: {
            Text(verbatim: "Last Send Failed at \(failure.date.formatted(.debugTime))")
        }
    }
}

private struct DebugFailureDetail: View {
    let failure: DavyDebugSnapshot.Failure

    var body: some View {
        Form {
            Section {
                Text(failure.reason)
                    .font(.caption.monospaced())
                    .textSelection(.enabled)
                ShareLink(item: failure.reason) {
                    Label {
                        Text(verbatim: "Share Reason")
                    } icon: {
                        Image(systemName: "square.and.arrow.up")
                    }
                }
            } header: {
                Text(verbatim: "Failed at \(failure.date.formatted(.debugTime))")
            } footer: {
                Text(verbatim: "Pending events are retried automatically, or now with Flush Now.")
            }
        }
        .formStyle(.grouped)
        .navigationTitle(Text(verbatim: "Send Failure"))
    }
}

/// Pending and sent events together, most recently logged first, narrowed by a search and a
/// filter.
private struct DebugEventsList: View {
    let model: DebugEventsModel
    let sentHistoryLimit: Int
    let searchText: String
    @Binding var filter: DebugEventFilter

    var body: some View {
        let events = model.events.filter { filter.includes($0) && $0.matches(searchText: searchText) }
        List {
            if let lastFailure = model.lastFailure {
                DebugFailureSection(failure: lastFailure)
            }
            if !model.events.isEmpty {
                Section {
                    Picker(selection: $filter) {
                        ForEach(DebugEventFilter.allCases) { filter in
                            Text(verbatim: filter.title)
                                .tag(filter)
                        }
                    } label: {
                        Text(verbatim: "Show")
                    }
                } footer: {
                    if events.count != model.events.count {
                        Text(verbatim: "Showing \(events.count) of \(model.events.count) events.")
                    }
                }
            }
            Section {
                ForEach(events) { item in
                    NavigationLink(value: item.event) {
                        DebugEventRow(event: item.event, status: item.status)
                    }
                }
                if events.isEmpty && !model.events.isEmpty {
                    ContentUnavailableView {
                        Label {
                            Text(verbatim: "No Matching Events")
                        } icon: {
                            Image(systemName: "magnifyingglass")
                        }
                    } description: {
                        Text(verbatim: filter.noMatchesDescription(searchText: searchText))
                    }
                }
            } footer: {
                if !events.isEmpty {
                    DebugSentHistoryNote(limit: sentHistoryLimit)
                }
            }
        }
        .overlay {
            if !model.isLoaded {
                ProgressView()
            } else if model.events.isEmpty && model.lastFailure == nil {
                ContentUnavailableView {
                    Label {
                        Text(verbatim: "No Events")
                    } icon: {
                        Image(systemName: "tray")
                    }
                } description: {
                    if sentHistoryLimit > 0 {
                        Text(verbatim: "Logged events are listed here, and stay listed once sent until the app quits.")
                    } else {
                        Text(verbatim: "Logged events are listed here until they're sent.")
                    }
                }
            }
        }
    }
}

private struct DebugSentHistoryNote: View {
    let limit: Int

    var body: some View {
        if limit > 0 {
            Text(verbatim: "Sent events are kept until the app quits, up to \(limit).")
        } else {
            Text(verbatim: "Sent events aren't kept because `debugHistoryLimit` is 0.")
        }
    }
}

private struct DebugEventRow: View {
    let event: DavyDebugSnapshot.LoggedEvent
    let status: DebugEventStatus

    var body: some View {
        HStack(spacing: 12) {
            DebugEventStatusIcon(status: status)
            VStack(alignment: .leading, spacing: 2) {
                Text(event.name)
                    .font(.body.monospaced())
                HStack(spacing: 8) {
                    Text(event.timestamp, format: .debugTime)
                    Text(event.id.uuidString.prefix(8))
                        .monospaced()
                    if event.isError {
                        Text(verbatim: "Error")
                            .foregroundStyle(.red)
                    }
                    if case let .sent(_, .rejected(statusCode)) = status {
                        Text(verbatim: "Rejected (HTTP \(statusCode))")
                            .foregroundStyle(.red)
                    }
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
        }
    }
}

/// Shows whether an event has been sent.
private struct DebugEventStatusIcon: View {
    let status: DebugEventStatus

    var body: some View {
        Image(systemName: status.symbolName)
            .foregroundStyle(status.color)
            .accessibilityLabel(Text(verbatim: status.title))
    }
}

/// Looks up the event's current status and requests, so they update if the event is sent while
/// shown.
private struct DebugEventDestination: View {
    let event: DavyDebugSnapshot.LoggedEvent
    let model: DebugEventsModel

    var body: some View {
        DebugEventDetail(event: event, status: model.status(of: event.id), requests: model.requests(containing: event.id))
    }
}

private struct DebugEventDetail: View {
    let event: DavyDebugSnapshot.LoggedEvent
    let status: DebugEventStatus
    let requests: [DavyDebugSnapshot.Request]

    var body: some View {
        Form {
            DebugEventSummarySection(event: event, status: status)
            DebugEventInfoSection(info: event.info)
            DebugEventSessionSection(session: event.session)
            if !requests.isEmpty {
                Section {
                    ForEach(requests) { request in
                        NavigationLink(value: request) {
                            DebugRequestRow(request: request)
                        }
                    }
                } header: {
                    Text(verbatim: "Requests")
                }
            }
            DebugEventJSONSection(event: event)
        }
        .formStyle(.grouped)
        .navigationTitle(event.name)
    }
}

private struct DebugEventSummarySection: View {
    let event: DavyDebugSnapshot.LoggedEvent
    let status: DebugEventStatus

    var body: some View {
        Section {
            DebugField("Name") {
                Text(event.name)
                    .monospaced()
            }
            DebugField("Type") {
                event.isError ? Text(verbatim: "Error") : Text(verbatim: "Breadcrumb")
            }
            DebugField("ID") {
                Text(event.id.uuidString)
                    .font(.caption.monospaced())
            }
            DebugField("Logged") {
                Text(event.timestamp, format: .dateTime.year().month().day().hour().minute().second().secondFraction(.fractional(3)))
            }
            DebugField("Status") {
                DebugEventStatusText(status: status)
            }
        }
        .textSelection(.enabled)
    }
}

private struct DebugEventStatusText: View {
    let status: DebugEventStatus

    var body: some View {
        switch status {
        case .pending:
            Text(verbatim: "Pending")
        case let .sent(date, .delivered):
            Text(verbatim: "Sent at \(date.formatted(.debugTime))")
        case let .sent(date, .rejected(statusCode)):
            Text(verbatim: "Rejected with HTTP \(statusCode) at \(date.formatted(.debugTime))")
                .foregroundStyle(.red)
        case .unknown:
            Text(verbatim: "No longer pending")
        }
    }
}

private struct DebugEventInfoSection: View {
    let info: [String: String]

    var body: some View {
        Section {
            if info.isEmpty {
                Text(verbatim: "None")
                    .foregroundStyle(.secondary)
            } else {
                ForEach(info.sorted(by: <), id: \.key) { entry in
                    LabeledContent(entry.key) {
                        Text(entry.value)
                    }
                }
            }
        } header: {
            Text(verbatim: "Info")
        }
        .textSelection(.enabled)
    }
}

private struct DebugEventSessionSection: View {
    let session: Session

    var body: some View {
        Section {
            DebugField("ID") {
                Text(session.id.uuidString)
                    .font(.caption.monospaced())
            }
            DebugField("Environment") {
                Text(session.environment ?? "—")
            }
            DebugField("App Version") {
                Text(session.appVersion ?? "—")
            }
            DebugField("Build") {
                Text(session.build ?? "—")
            }
            DebugField("OS") {
                Text(verbatim: "\(session.os) \(session.osVersion)")
            }
            DebugField("Device Model") {
                Text(session.deviceModel ?? "—")
            }
            DebugField("Locale") {
                Text(session.locale ?? "—")
            }
            if let info = session.info {
                ForEach(info.sorted(by: <), id: \.key) { entry in
                    LabeledContent(entry.key) {
                        Text(entry.value)
                    }
                }
            }
        } header: {
            Text(verbatim: "Session")
        }
        .textSelection(.enabled)
    }
}

private struct DebugEventJSONSection: View {
    let event: DavyDebugSnapshot.LoggedEvent

    var body: some View {
        let json = event.prettyJSON
        Section {
            Text(json)
                .font(.caption.monospaced())
                .textSelection(.enabled)
            ShareLink(item: json) {
                Label {
                    Text(verbatim: "Share JSON")
                } icon: {
                    Image(systemName: "square.and.arrow.up")
                }
            }
        } header: {
            Text(verbatim: "JSON")
        }
    }
}

/// Requests that finished, newest first, narrowed by a search.
private struct DebugRequestsList: View {
    let model: DebugEventsModel
    let historyLimit: Int
    let searchText: String

    var body: some View {
        let requests = model.requests.filter { $0.matches(searchText: searchText) }
        List {
            if let lastFailure = model.lastFailure {
                DebugFailureSection(failure: lastFailure)
            }
            Section {
                ForEach(requests) { request in
                    NavigationLink(value: request) {
                        DebugRequestRow(request: request)
                    }
                }
                if requests.isEmpty && !model.requests.isEmpty {
                    ContentUnavailableView {
                        Label {
                            Text(verbatim: "No Matching Requests")
                        } icon: {
                            Image(systemName: "magnifyingglass")
                        }
                    } description: {
                        Text(verbatim: "No requests match the search.")
                    }
                }
            } footer: {
                if !requests.isEmpty {
                    Text(verbatim: "Requests are kept until the app quits, up to \(historyLimit).")
                }
            }
        }
        .overlay {
            if !model.isLoaded {
                ProgressView()
            } else if model.requests.isEmpty && model.lastFailure == nil {
                ContentUnavailableView {
                    Label {
                        Text(verbatim: "No Requests")
                    } icon: {
                        Image(systemName: "network")
                    }
                } description: {
                    if historyLimit > 0 {
                        Text(verbatim: "Requests are listed here once they finish, until the app quits, up to \(historyLimit).")
                    } else {
                        Text(verbatim: "Requests aren't kept because `debugHistoryLimit` is 0.")
                    }
                }
            }
        }
    }
}

private struct DebugRequestRow: View {
    let request: DavyDebugSnapshot.Request

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: request.outcome.symbolName)
                .foregroundStyle(request.outcome.color)
                .accessibilityLabel(Text(verbatim: request.outcome.title))
            VStack(alignment: .leading, spacing: 2) {
                Text(verbatim: request.eventCountTitle)
                    .font(.body)
                HStack(spacing: 8) {
                    Text(request.sentAt, format: .debugTime)
                    Text(verbatim: request.statusTitle)
                        .foregroundStyle(request.outcome == .delivered ? Color.secondary : request.outcome.color)
                    Text(verbatim: request.durationTitle)
                }
                .font(.caption)
                .foregroundStyle(.secondary)
            }
        }
    }
}

/// Looks up the request's events, so their statuses update while shown.
private struct DebugRequestDestination: View {
    let request: DavyDebugSnapshot.Request
    let model: DebugEventsModel

    var body: some View {
        DebugRequestDetail(request: request, events: request.eventIDs.map { (id: $0, item: model.item(for: $0)) })
    }
}

private struct DebugRequestDetail: View {
    let request: DavyDebugSnapshot.Request
    /// The request's events, with each one's item if it's still pending or in the sent history.
    let events: [(id: UUID, item: DebugEventItem?)]

    var body: some View {
        let body = request.prettyBody
        Form {
            Section {
                DebugField("Sent") {
                    Text(request.sentAt, format: .dateTime.year().month().day().hour().minute().second().secondFraction(.fractional(3)))
                }
                DebugField("Duration") {
                    Text(verbatim: request.durationTitle)
                }
                DebugField("Status") {
                    Text(verbatim: request.statusTitle)
                }
                DebugField("Outcome") {
                    Text(verbatim: request.outcome.detail)
                        .foregroundStyle(request.outcome == .delivered ? Color.primary : request.outcome.color)
                }
            }
            .textSelection(.enabled)
            if case let .failed(reason) = request.outcome {
                Section {
                    Text(reason)
                        .font(.caption.monospaced())
                        .textSelection(.enabled)
                } header: {
                    Text(verbatim: "Reason")
                }
            }
            Section {
                ForEach(events, id: \.id) { event in
                    if let item = event.item {
                        NavigationLink(value: item.event) {
                            DebugEventRow(event: item.event, status: item.status)
                        }
                    } else {
                        Text(event.id.uuidString)
                            .font(.caption.monospaced())
                            .textSelection(.enabled)
                    }
                }
            } header: {
                Text(verbatim: "Events (\(events.count))")
            } footer: {
                if events.contains(where: { $0.item == nil }) {
                    Text(verbatim: "Events shown by ID are no longer pending or in the sent history.")
                }
            }
            Section {
                if request.headers.isEmpty {
                    Text(verbatim: "None")
                        .foregroundStyle(.secondary)
                } else {
                    ForEach(request.headers.sorted(by: <), id: \.key) { header in
                        LabeledContent(header.key) {
                            Text(header.value)
                                .font(.caption.monospaced())
                        }
                    }
                }
            } header: {
                Text(verbatim: "Headers")
            }
            .textSelection(.enabled)
            Section {
                Text(body)
                    .font(.caption.monospaced())
                    .textSelection(.enabled)
                ShareLink(item: body) {
                    Label {
                        Text(verbatim: "Share Body")
                    } icon: {
                        Image(systemName: "square.and.arrow.up")
                    }
                }
            } header: {
                Text(verbatim: "Body")
            }
        }
        .formStyle(.grouped)
        .navigationTitle(Text(verbatim: "Request"))
    }
}

/// A row whose title, like all of this view's text, isn't localized.
private struct DebugField<Value: View>: View {
    let title: String
    let value: Value

    init(_ title: String, @ViewBuilder value: () -> Value) {
        self.title = title
        self.value = value()
    }

    var body: some View {
        LabeledContent {
            value
        } label: {
            Text(verbatim: title)
        }
    }
}

private extension DebugEventStatus {
    var title: String {
        switch self {
        case .pending: "Pending"
        case .sent(_, .delivered): "Sent"
        case .sent(_, .rejected): "Rejected"
        case .unknown: "No longer pending"
        }
    }

    var symbolName: String {
        switch self {
        case .pending: "clock"
        case .sent(_, .delivered): "checkmark.circle.fill"
        case .sent(_, .rejected): "xmark.circle.fill"
        case .unknown: "questionmark.circle"
        }
    }

    var color: Color {
        switch self {
        case .pending: .orange
        case .sent(_, .delivered): DebugActivitySeries.sent.color
        case .sent(_, .rejected): .red
        case .unknown: .secondary
        }
    }
}

private extension DavyDebugSnapshot.Request {
    /// In whole milliseconds, for example `123 ms`.
    var durationTitle: String {
        "\(Int((duration * 1000).rounded())) ms"
    }
}

private extension DavyDebugSnapshot.Request.Outcome {
    var title: String {
        switch self {
        case .delivered: "Delivered"
        case .rejected: "Rejected"
        case .throttled: "Throttled"
        case .failed: "Failed"
        }
    }

    /// The title, with how long the server asked to pause for. A failure's reason, which can be
    /// long, is shown separately.
    var detail: String {
        switch self {
        case .delivered, .rejected, .failed: title
        case let .throttled(retryAfter): "Throttled: retry after \(Int(retryAfter.rounded(.up))) s"
        }
    }

    var symbolName: String {
        switch self {
        case .delivered: "checkmark.circle.fill"
        case .rejected: "xmark.circle.fill"
        case .throttled: "pause.circle.fill"
        case .failed: "exclamationmark.triangle.fill"
        }
    }

    var color: Color {
        switch self {
        case .delivered: DebugActivitySeries.sent.color
        case .rejected: .red
        case .throttled, .failed: .orange
        }
    }
}

private extension ToolbarItemPlacement {
    /// On iOS, below the list, since the navigation bar only has room for Done.
    static var debugActions: Self {
        #if os(iOS)
        .bottomBar
        #else
        .primaryAction
        #endif
    }
}

private extension FormatStyle where Self == Date.FormatStyle {
    /// Hours, minutes, and seconds, to tell apart events logged close together.
    static var debugTime: Self {
        .dateTime.hour().minute().second()
    }
}
