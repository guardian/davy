import Foundation
import Testing
@testable import Davy

/// The same cases as the Android client's `DebugEventQueryTest`.
struct DebugEventFilterTests {
    private let session = Session(
        id: UUID(), environment: nil, appVersion: "1.2.0", build: "45",
        os: "iOS", osVersion: "26.1.0", deviceModel: "iPhone17,1", locale: "en_GB"
    )
    private let snapshot: DavyDebugSnapshot

    init() throws {
        let pending = loggedEvent(.breadcrumb("checkout_started", info: ["step": "Cart"]), at: 1, session: session)
        let delivered = loggedEvent(
            .error("payment_declined", info: ["reason": "card_expired"]),
            at: 2,
            session: session,
            id: try #require(UUID(uuidString: "9C5B94B1-35AD-49BB-B118-8E8FC24ABF80"))
        )
        let rejected = loggedEvent(.breadcrumb("bad_event"), at: 3, session: session)
        snapshot = DavyDebugSnapshot(
            // An event in a request that's finished is listed once, as sent.
            pending: [pending, delivered],
            sent: [
                DavyDebugSnapshot.SentEvent(event: delivered, sentAt: Date(timeIntervalSince1970: 5), outcome: .delivered),
                DavyDebugSnapshot.SentEvent(event: rejected, sentAt: Date(timeIntervalSince1970: 6), outcome: .rejected(statusCode: 400)),
            ],
            requests: [],
            loggedCount: 3,
            sentCount: 2,
            lastFailure: nil,
            activity: [],
            sessionStarts: []
        )
    }

    private func names(_ filter: DebugEventFilter = .all, searchText: String = "") -> [String] {
        DebugEventItem.all(in: snapshot)
            .filter { filter.includes($0) && $0.matches(searchText: searchText) }
            .map(\.event.name)
    }

    @Test func eventsAreListedOnceMostRecentlyLoggedFirst() {
        #expect(names() == ["bad_event", "payment_declined", "checkout_started"])
    }

    @Test func eventsLoggedAtTheSameTimeAreListedMostRecentlyLoggedFirst() {
        let first = loggedEvent(.breadcrumb("first"), at: 1, session: session)
        let second = loggedEvent(.breadcrumb("second"), at: 1, session: session)
        let sameTime = DavyDebugSnapshot(
            pending: [first, second],
            sent: [],
            requests: [],
            loggedCount: 2,
            sentCount: 0,
            lastFailure: nil,
            activity: [],
            sessionStarts: []
        )

        #expect(DebugEventItem.all(in: sameTime).map(\.event.name) == ["second", "first"])
    }

    @Test func filtersShowEventsByStatusOrType() {
        #expect(names(.pending) == ["checkout_started"])
        #expect(names(.sent) == ["payment_declined"])
        #expect(names(.rejected) == ["bad_event"])
        #expect(names(.errors) == ["payment_declined"])
    }

    @Test func searchMatchesNamesInfoAndIDsIgnoringCase() {
        #expect(names(searchText: "  ") == names())
        #expect(names(searchText: "CHECKOUT") == ["checkout_started"])
        #expect(names(searchText: "cart") == ["checkout_started"])
        #expect(names(searchText: "Reason") == ["payment_declined"])
        #expect(names(searchText: " 9c5b94b1 ") == ["payment_declined"])
        #expect(names(searchText: "refund").isEmpty)
        #expect(names(.pending, searchText: "payment").isEmpty)
    }

    @Test func emptyResultsExplainWhatNarrowedThem() {
        #expect(DebugEventFilter.sent.noMatchesDescription(searchText: " ") == "No events match the filter.")
        #expect(DebugEventFilter.all.noMatchesDescription(searchText: "refund") == "No events match the search.")
        #expect(DebugEventFilter.sent.noMatchesDescription(searchText: "refund") == "No events match the search and filter.")
    }

    @Test func requestSearchMatchesTheDecodedURLOrBodyIgnoringCase() throws {
        let queryString = request(
            url: "https://api.example.com/events/checkout_started?type=breadcrumb&info=%7B%22step%22%3A%22Cart%22%7D",
            body: nil
        )
        let batch = request(url: "https://api.example.com/events", body: #"{"events":[{"name":"payment_declined"}]}"#)

        let matching = { (text: String) in [queryString, batch].filter { $0.matches(searchText: text) }.map(\.url) }
        #expect(matching("  ") == [queryString.url, batch.url])
        #expect(matching(#""step":"cart""#) == [queryString.url])
        #expect(matching("PAYMENT_DECLINED") == [batch.url])
        #expect(matching("refund").isEmpty)
    }

    @Test func requestBodiesAreShownIndentedWithSortedKeys() {
        let json = request(url: "https://api.example.com/events", body: #"{"session":{"id":"a/b"},"name":"x"}"#)
        #expect(json.prettyBody == """
            {
              "name" : "x",
              "session" : {
                "id" : "a/b"
              }
            }
            """)
        #expect(request(url: "https://api.example.com/events", body: "not json").prettyBody == "not json")
        #expect(request(url: "https://api.example.com/events/a", body: nil).prettyBody == nil)
        #expect(request(url: "https://api.example.com/events/a", body: nil, statusCode: nil).statusTitle == "No response")
        #expect(json.statusTitle == "HTTP 200")
    }
}

private func request(url: String, body: String?, statusCode: Int? = 200) -> DavyDebugSnapshot.Request {
    DavyDebugSnapshot.Request(
        id: UUID(),
        method: "POST",
        url: URL(string: url)!,
        headers: [:],
        body: body,
        eventIDs: [UUID()],
        sentAt: Date(timeIntervalSince1970: 1),
        duration: 0.1,
        statusCode: statusCode,
        outcome: .delivered
    )
}

private func loggedEvent(
    _ event: Davy.Event,
    at seconds: TimeInterval,
    session: Session,
    id: UUID = UUID()
) -> DavyDebugSnapshot.LoggedEvent {
    DavyDebugSnapshot.LoggedEvent(record: EventRecord(event, timestamp: Date(timeIntervalSince1970: seconds), id: id), session: session)
}
