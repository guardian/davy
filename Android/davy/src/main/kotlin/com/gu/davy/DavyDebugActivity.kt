package com.gu.davy

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Spinner
import android.widget.SearchView
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import java.io.Closeable
import java.lang.ref.WeakReference
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Lists the events a [Davy] instance is waiting to send and the events it has recently sent, so
 * you can check your events during development.
 *
 * The top of the list shows the current session ID, and how many events were logged since launch
 * and how many of those are pending and sent. Below, pending and sent events are listed together, most
 * recently logged first, with a mark showing whether each has been sent. Select an event to see
 * everything it's sent with, including its JSON. The menu sends pending events now and clears the
 * sent history and requests. If sending fails, the reason is shown at the top; select it to see all
 * of it.
 *
 * Select Requests to list the requests that finished, most recent first, with a mark showing how
 * the server responded to each. Select a request to see its events, headers, body, and response. An
 * event's details list the requests that included it.
 *
 * Start it from a debug menu:
 *
 * ```kotlin
 * DavyDebugActivity.start(context, telemetry)
 * ```
 *
 * Events are listed as sent, and requests are listed, only if they were sent since the instance was
 * created, up to [DavyConfiguration.debugHistoryLimit]. Unlike the Swift client's debug view, it doesn't chart
 * each minute's activity, since Android has no built-in chart; the counts are in
 * [DavyDebugSnapshot.activity]. It's a development tool, so its text is in English and isn't
 * localized.
 */
@SuppressLint("SetTextI18n")
public class DavyDebugActivity : Activity() {
    private var telemetry: Davy? = null
    private var listener: Closeable? = null
    private var snapshot: DavyDebugSnapshot? = null
    private var render: (DavyDebugSnapshot) -> Unit = {}
    private var clearItem: MenuItem? = null

    /** Text that listed events' names, `info`, or IDs, or listed requests' bodies, must contain. */
    internal var searchText: String = ""
        set(value) {
            field = value
            snapshot?.let(render)
        }

    /** Which events are listed. */
    internal var eventFilter: DebugEventFilter = DebugEventFilter.ALL
        set(value) {
            field = value
            snapshot?.let(render)
        }

    /** The events listed, for tests. */
    internal var listedEvents: List<DavyDebugSnapshot.LoggedEvent> = emptyList()
        private set

    /** The requests listed, for tests. */
    internal var listedRequests: List<DavyDebugSnapshot.Request> = emptyList()
        private set

    /** Whether this screen lists requests rather than events. */
    private val showsRequests: Boolean get() = intent.getBooleanExtra(EXTRA_REQUESTS, false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        actionBar?.setDisplayHomeAsUpEnabled(true)
        telemetry = intent.getStringExtra(EXTRA_SESSION_ID)?.let(::instance)
        val eventId = intent.getStringExtra(EXTRA_EVENT_ID)
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
        val failureReason = intent.getStringExtra(EXTRA_FAILURE_REASON)
        when {
            telemetry == null -> showMissingInstance()
            eventId != null -> showEvent(UUID.fromString(eventId))
            requestId != null -> showRequest(UUID.fromString(requestId))
            failureReason != null -> showFailure(failureReason, intent.getLongExtra(EXTRA_FAILURE_DATE, 0))
            showsRequests -> showRequests()
            else -> showEvents()
        }
    }

    override fun onStart() {
        super.onStart()
        listener = telemetry?.addDebugListener { snapshot ->
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                this.snapshot = snapshot
                render(snapshot)
                // Rather than invalidating the menu, which would close the search field.
                clearItem?.isEnabled = snapshot.hasHistory
            }
        }
    }

    override fun onStop() {
        listener?.close()
        listener = null
        super.onStop()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (telemetry == null || listOf(EXTRA_EVENT_ID, EXTRA_REQUEST_ID, EXTRA_FAILURE_REASON).any(intent::hasExtra)) return false
        val search = SearchView(actionBar?.themedContext ?: this).apply {
            queryHint = if (showsRequests) "Body" else "Name, info, or ID"
            setOnQueryTextListener(
                object : SearchView.OnQueryTextListener {
                    override fun onQueryTextChange(newText: String): Boolean {
                        searchText = newText
                        return true
                    }

                    override fun onQueryTextSubmit(query: String): Boolean {
                        clearFocus()
                        return true
                    }
                },
            )
        }
        menu.add(Menu.NONE, MENU_SEARCH, Menu.NONE, "Search").apply {
            setIcon(android.R.drawable.ic_menu_search)
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM or MenuItem.SHOW_AS_ACTION_COLLAPSE_ACTION_VIEW)
            actionView = search
            setOnActionExpandListener(
                object : MenuItem.OnActionExpandListener {
                    override fun onMenuItemActionExpand(item: MenuItem) = true

                    override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                        searchText = ""
                        return true
                    }
                },
            )
        }
        menu.add(Menu.NONE, MENU_FLUSH, Menu.NONE, "Flush Now").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        clearItem = menu.add(Menu.NONE, MENU_CLEAR, Menu.NONE, "Clear Sent History").apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(MENU_CLEAR)?.isEnabled = snapshot?.hasHistory == true
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        MENU_FLUSH -> {
            telemetry?.flush()
            true
        }
        MENU_CLEAR -> {
            telemetry?.clearDebugHistory()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun showMissingInstance() {
        title = "Events"
        setContentView(
            page {
                addView(text("No Davy Instance", size = 20f, bold = true))
                addView(caption("Start this screen with DavyDebugActivity.start(context, telemetry) while the instance exists."))
            },
        )
    }

    private fun showEvents() {
        val telemetry = telemetry ?: return
        title = "Events"
        val sessionId = monospace(DavyJson.uuid(telemetry.sessionId))
        val pending = stat()
        val logged = stat()
        val sent = stat()
        val failureHeader = sectionHeader("")
        val failureReason = text("", size = 12f, monospace = true).apply {
            maxLines = 3
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(WARNING)
            setPadding(0, dp(4), 0, dp(4))
        }
        val failure = vertical(failureHeader, failureReason)
        val requestsHeader = sectionHeader("Requests")
        val lastRequest = caption("")
        val requestsRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(4), 0, dp(4))
            addView(vertical(text("Show Requests"), lastRequest), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(text("›", size = 22f).apply { setTextColor(SECONDARY) })
            selectable { startActivity(screenIntent().putExtra(EXTRA_REQUESTS, true)) }
        }
        val eventsHeader = sectionHeader("Events")
        val filter = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(text("Show"), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(
                Spinner(this@DavyDebugActivity).apply {
                    adapter = ArrayAdapter(this@DavyDebugActivity, android.R.layout.simple_spinner_item, DebugEventFilter.entries.map { it.title })
                        .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
                    setSelection(eventFilter.ordinal)
                    onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                        override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                            eventFilter = DebugEventFilter.entries[position]
                        }

                        override fun onNothingSelected(parent: AdapterView<*>) = Unit
                    }
                },
            )
        }
        val note = caption("")

        val header = vertical(
            sectionHeader("Summary"),
            field("Session ID", sessionId),
            LinearLayout(this).apply {
                setPadding(0, dp(12), 0, 0)
                listOf(logged to "Logged", pending to "Pending", sent to "Sent").forEach { (value, title) ->
                    addView(vertical(value, caption(title)), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
            },
            caption("Since launch, including events left over from earlier launches. Every logged event is pending or sent."),
            failure,
            requestsHeader,
            requestsRow,
            eventsHeader,
            filter,
        ).apply { setPadding(dp(16), 0, dp(16), dp(8)) }
        val footer = vertical(note).apply { setPadding(dp(16), dp(8), dp(16), dp(24)) }

        val adapter = RowsAdapter<DebugEventItem>(::bind)
        val list = ListView(this).apply {
            fitsSystemWindows = true
            clipToPadding = false
            addHeaderView(header, null, false)
            addFooterView(footer, null, false)
            this.adapter = adapter
            setOnItemClickListener { parent, _, position, _ ->
                val item = parent.getItemAtPosition(position) as? DebugEventItem ?: return@setOnItemClickListener
                startActivity(screenIntent().putExtra(EXTRA_EVENT_ID, item.event.id.toString()))
            }
        }
        setContentView(list)

        render = { snapshot ->
            val items = DebugEventItem.all(snapshot)
            val listed = items.filter { eventFilter.includes(it) && it.matches(searchText) }
            listedEvents = listed.map { it.event }
            adapter.items = listed
            logged.text = snapshot.loggedCount.toString()
            pending.text = snapshot.pending.size.toString()
            sent.text = snapshot.sentCount.toString()
            val lastFailure = snapshot.lastFailure
            failure.visibility = if (lastFailure == null) View.GONE else View.VISIBLE
            if (lastFailure != null) {
                failureHeader.text = "Last Send Failed at ${time(lastFailure.dateMillis)}".uppercase(Locale.ROOT)
                failureReason.text = "⚠ ${lastFailure.reason}"
                failure.setOnClickListener {
                    startActivity(
                        screenIntent()
                            .putExtra(EXTRA_FAILURE_REASON, lastFailure.reason)
                            .putExtra(EXTRA_FAILURE_DATE, lastFailure.dateMillis),
                    )
                }
            }
            val count = if (listed.size == items.size) "${items.size}" else "${listed.size} of ${items.size}"
            eventsHeader.text = "Events ($count)".uppercase(Locale.ROOT)
            filter.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
            val limit = telemetry.configuration.debugHistoryLimit
            requestsHeader.text = "Requests (${snapshot.requests.size})".uppercase(Locale.ROOT)
            val last = snapshot.requests.lastOrNull()
            lastRequest.text = when {
                last != null -> "Last: ${last.statusTitle} at ${time(last.sentAtMillis)}"
                limit > 0 -> "None yet."
                else -> "Not kept because debugHistoryLimit is 0."
            }
            note.text = when {
                items.isEmpty() && limit > 0 -> "No events. Logged events are listed here, and stay listed once sent until the app quits."
                items.isEmpty() -> "No events. Logged events are listed here until they're sent."
                listed.isEmpty() -> eventFilter.noMatchesDescription(searchText)
                limit > 0 -> "Sent events are kept until the app quits, up to $limit."
                else -> "Sent events aren't kept because debugHistoryLimit is 0."
            }
        }
    }

    private fun showEvent(id: UUID) {
        val content = vertical()
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(content)
        })
        content.setPadding(dp(16), 0, dp(16), dp(24))
        var event: DavyDebugSnapshot.LoggedEvent? = null

        render = { snapshot ->
            val items = DebugEventItem.all(snapshot)
            event = event ?: items.firstOrNull { it.event.id == id }?.event
            content.removeAllViews()
            val shown = event
            if (shown == null) {
                title = "Event"
                content.addView(caption("This event is no longer listed."))
            } else {
                title = shown.name
                val status = items.firstOrNull { it.event.id == id }?.status ?: DebugEventStatus.Unknown
                addEventDetail(content, shown, status, snapshot.requestsContaining(id))
            }
        }
    }

    private fun addEventDetail(
        content: LinearLayout,
        event: DavyDebugSnapshot.LoggedEvent,
        status: DebugEventStatus,
        requests: List<DavyDebugSnapshot.Request>,
    ) {
        content.addView(sectionHeader("Event"))
        content.addView(field("Name", monospace(event.name)))
        content.addView(field("Type", text(if (event.isError) "Error" else "Breadcrumb")))
        content.addView(field("ID", monospace(DavyJson.uuid(event.id))))
        content.addView(field("Logged", text(FULL_TIME.get()!!.format(Date(event.timestampMillis)))))
        content.addView(field("Status", statusText(status)))

        if (requests.isNotEmpty()) {
            content.addView(sectionHeader("Requests"))
            requests.forEach { content.addView(requestRow(it)) }
        }

        content.addView(sectionHeader("Info"))
        if (event.info.isEmpty()) {
            content.addView(caption("None"))
        } else {
            event.info.toSortedMap().forEach { (key, value) -> content.addView(field(key, text(value))) }
        }

        val session = event.session
        content.addView(sectionHeader("Session"))
        content.addView(field("ID", monospace(DavyJson.uuid(session.id))))
        content.addView(field("Environment", text(session.environment ?: "—")))
        content.addView(field("App Version", text(session.appVersion ?: "—")))
        content.addView(field("Build", text(session.build ?: "—")))
        content.addView(field("OS", text("${session.os} ${session.osVersion}")))
        content.addView(field("Device Model", text(session.deviceModel ?: "—")))
        content.addView(field("Locale", text(session.locale ?: "—")))
        session.info?.toSortedMap()?.forEach { (key, value) -> content.addView(field(key, text(value))) }

        val json = event.prettyJson
        content.addView(sectionHeader("JSON"))
        content.addView(text(json, size = 12f, monospace = true, selectable = true))
        content.addView(shareButton("Share JSON", json))
    }

    private fun showFailure(reason: String, dateMillis: Long) {
        title = "Send Failure"
        setContentView(
            page {
                addView(sectionHeader("Failed at ${time(dateMillis)}"))
                addView(text(reason, size = 12f, monospace = true, selectable = true))
                addView(shareButton("Share Reason", reason))
                addView(caption("Pending events are retried automatically, or now with Flush Now."))
            },
        )
    }

    private fun showRequests() {
        val telemetry = telemetry ?: return
        title = "Requests"
        val requestsHeader = sectionHeader("Requests")
        val note = caption("")
        val header = vertical(requestsHeader).apply { setPadding(dp(16), 0, dp(16), dp(8)) }
        val footer = vertical(note).apply { setPadding(dp(16), dp(8), dp(16), dp(24)) }

        val adapter = RowsAdapter<DavyDebugSnapshot.Request>(::bind)
        val list = ListView(this).apply {
            fitsSystemWindows = true
            clipToPadding = false
            addHeaderView(header, null, false)
            addFooterView(footer, null, false)
            this.adapter = adapter
            setOnItemClickListener { parent, _, position, _ ->
                val request = parent.getItemAtPosition(position) as? DavyDebugSnapshot.Request ?: return@setOnItemClickListener
                startActivity(screenIntent().putExtra(EXTRA_REQUEST_ID, request.id.toString()))
            }
        }
        setContentView(list)

        render = { snapshot ->
            val requests = snapshot.requestsNewestFirst()
            val listed = requests.filter { it.matches(searchText) }
            listedRequests = listed
            adapter.items = listed
            val count = if (listed.size == requests.size) "${requests.size}" else "${listed.size} of ${requests.size}"
            requestsHeader.text = "Requests ($count)".uppercase(Locale.ROOT)
            val limit = telemetry.configuration.debugHistoryLimit
            note.text = when {
                limit <= 0 -> "Requests aren't kept because debugHistoryLimit is 0."
                requests.isEmpty() -> "Requests are listed here once they finish, until the app quits, up to $limit."
                listed.isEmpty() -> "No requests match the search."
                else -> "Requests are kept until the app quits, up to $limit."
            }
        }
    }

    private fun showRequest(id: UUID) {
        title = "Request"
        val content = vertical()
        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            addView(content)
        })
        content.setPadding(dp(16), 0, dp(16), dp(24))
        var request: DavyDebugSnapshot.Request? = null
        var shownState: List<Any?>? = null

        render = { snapshot ->
            request = request ?: snapshot.requests.firstOrNull { it.id == id }
            val shown = request
            val items = DebugEventItem.all(snapshot).associateBy { it.event.id }
            val events = shown?.eventIds.orEmpty().map(items::get)
            // Rebuilt only when what's shown changes, so selecting the body's text isn't interrupted.
            val state = listOf<Any?>(shown) + events.map { it?.status }
            if (state != shownState) {
                shownState = state
                content.removeAllViews()
                if (shown == null) {
                    content.addView(caption("This request is no longer listed."))
                } else {
                    addRequestDetail(content, shown, events)
                }
            }
        }
    }

    /** [request]'s details, with [events], the request's events in order, or `null` for any no longer listed. */
    private fun addRequestDetail(content: LinearLayout, request: DavyDebugSnapshot.Request, events: List<DebugEventItem?>) {
        content.addView(sectionHeader("Summary"))
        content.addView(field("Sent At", text(FULL_TIME.get()!!.format(Date(request.sentAtMillis)))))
        content.addView(field("Duration", text("${request.durationMillis} ms")))
        content.addView(field("Status Code", text(request.statusCode?.toString() ?: "No response")))
        val outcome = text(request.outcome.description)
        if (request.outcome != DavyDebugSnapshot.Request.Outcome.Delivered) outcome.setTextColor(outcomeMark(request.outcome).second)
        content.addView(field("Outcome", outcome))

        val failure = request.outcome as? DavyDebugSnapshot.Request.Outcome.Failed
        if (failure != null) {
            content.addView(sectionHeader("Reason"))
            content.addView(text(failure.reason, size = 12f, monospace = true, selectable = true))
        }

        content.addView(sectionHeader("Events (${request.eventIds.size})"))
        request.eventIds.zip(events).forEach { (eventId, item) ->
            if (item == null) {
                content.addView(field("No longer listed", monospace(DavyJson.uuid(eventId))))
            } else {
                content.addView(
                    listRow().apply {
                        bind(tag as RowViews, item)
                        selectable { startActivity(screenIntent().putExtra(EXTRA_EVENT_ID, eventId.toString())) }
                    },
                )
            }
        }

        content.addView(sectionHeader("Headers"))
        if (request.headers.isEmpty()) {
            content.addView(caption("None"))
        } else {
            request.headers.entries.sortedBy { it.key.lowercase(Locale.ROOT) }.forEach { (name, value) ->
                content.addView(monospace("$name: $value").apply { setPadding(0, dp(4), 0, dp(4)) })
            }
        }

        content.addView(sectionHeader("Body"))
        val body = request.prettyBody
        content.addView(text(body, size = 12f, monospace = true, selectable = true))
        content.addView(shareButton("Share Body", body))
    }

    /** A list of rows built with [listRow], each showing an item with [bind]. */
    private inner class RowsAdapter<T : Any>(private val bind: (RowViews, T) -> Unit) : BaseAdapter() {
        var items: List<T> = emptyList()
            set(value) {
                field = value
                notifyDataSetChanged()
            }

        override fun getCount() = items.size

        override fun getItem(position: Int) = items[position]

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView ?: listRow()
            bind(row.tag as RowViews, items[position])
            return row
        }
    }

    private class RowViews(val mark: TextView, val name: TextView, val details: TextView)

    /** An event or request in a list: a mark showing its status, its name, and details. */
    private fun listRow(): View {
        val mark = text("", size = 18f, bold = true).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(32), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val name = text("", size = 15f, monospace = true)
        val details = text("", size = 12f).apply { setTextColor(SECONDARY) }
        return LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(10), dp(16), dp(10))
            addView(mark)
            addView(vertical(name, details))
            tag = RowViews(mark, name, details)
        }
    }

    private fun bind(views: RowViews, item: DebugEventItem) {
        val (symbol, color, description) = when (val status = item.status) {
            DebugEventStatus.Pending -> Triple("○", WARNING, "Pending")
            is DebugEventStatus.Sent -> if (status.outcome is DavyDebugSnapshot.SentEvent.Outcome.Rejected) {
                Triple("✕", ERROR, "Rejected")
            } else {
                Triple("✓", SENT, "Sent")
            }
            DebugEventStatus.Unknown -> Triple("–", SECONDARY, "No longer pending")
        }
        views.mark.text = symbol
        views.mark.setTextColor(color)
        views.mark.contentDescription = description
        views.name.text = item.event.name
        views.details.text = SpannableStringBuilder().apply {
            append(time(item.event.timestampMillis)).append("  ")
            append(DavyJson.uuid(item.event.id).take(8), TypefaceSpan("monospace"), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (item.event.isError) append("  ").append("Error", ForegroundColorSpan(ERROR), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            val outcome = (item.status as? DebugEventStatus.Sent)?.outcome
            if (outcome is DavyDebugSnapshot.SentEvent.Outcome.Rejected) {
                append("  ").append("Rejected (HTTP ${outcome.statusCode})", ForegroundColorSpan(ERROR), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }

    private fun bind(views: RowViews, request: DavyDebugSnapshot.Request) {
        val (symbol, color, description) = outcomeMark(request.outcome)
        views.mark.text = symbol
        views.mark.setTextColor(color)
        views.mark.contentDescription = description
        views.name.text = request.eventCountTitle
        views.details.text = SpannableStringBuilder().apply {
            if (request.outcome == DavyDebugSnapshot.Request.Outcome.Delivered) {
                append(request.statusTitle)
            } else {
                append(request.statusTitle, ForegroundColorSpan(color), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            append("  ").append(time(request.sentAtMillis))
            append("  ").append("${request.durationMillis} ms")
        }
    }

    /** A row showing [request] that opens its details. */
    private fun requestRow(request: DavyDebugSnapshot.Request): View = listRow().apply {
        bind(tag as RowViews, request)
        selectable { startActivity(screenIntent().putExtra(EXTRA_REQUEST_ID, request.id.toString())) }
    }

    /** The mark, its color, and a description of it for accessibility. */
    private fun outcomeMark(outcome: DavyDebugSnapshot.Request.Outcome): Triple<String, Int, String> = when (outcome) {
        DavyDebugSnapshot.Request.Outcome.Delivered -> Triple("✓", SENT, "Delivered")
        DavyDebugSnapshot.Request.Outcome.Rejected -> Triple("✕", ERROR, "Rejected")
        is DavyDebugSnapshot.Request.Outcome.Throttled -> Triple("‖", WARNING, "Throttled")
        is DavyDebugSnapshot.Request.Outcome.Failed -> Triple("⚠", WARNING, "Failed")
    }

    private fun statusText(status: DebugEventStatus): TextView = when (status) {
        DebugEventStatus.Pending -> text("Pending")
        is DebugEventStatus.Sent -> when (val outcome = status.outcome) {
            DavyDebugSnapshot.SentEvent.Outcome.Delivered -> text("Sent at ${time(status.atMillis)}")
            is DavyDebugSnapshot.SentEvent.Outcome.Rejected ->
                text("Rejected with HTTP ${outcome.statusCode} at ${time(status.atMillis)}").apply { setTextColor(ERROR) }
        }
        DebugEventStatus.Unknown -> text("No longer pending")
    }

    private fun screenIntent() = Intent(this, DavyDebugActivity::class.java).putExtra(EXTRA_SESSION_ID, intent.getStringExtra(EXTRA_SESSION_ID))

    /** Makes the view show a highlight when touched, and calls [onSelect] when it's selected. */
    private fun View.selectable(onSelect: () -> Unit) {
        val background = TypedValue()
        if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, background, true)) {
            setBackgroundResource(background.resourceId)
        }
        setOnClickListener { onSelect() }
    }

    private val DavyDebugSnapshot.hasHistory: Boolean get() = sent.isNotEmpty() || requests.isNotEmpty()

    private fun shareButton(title: String, value: String) = Button(this).apply {
        text = title
        setOnClickListener {
            val share = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, value)
            startActivity(Intent.createChooser(share, title))
        }
    }

    private fun page(build: LinearLayout.() -> Unit) = ScrollView(this).apply {
        fitsSystemWindows = true
        addView(vertical().apply {
            setPadding(dp(16), dp(16), dp(16), dp(24))
            build()
        })
    }

    private fun vertical(vararg children: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        children.forEach(::addView)
    }

    /** A labeled value. */
    private fun field(title: String, value: TextView) = vertical(caption(title).apply { setPadding(0, 0, 0, 0) }, value).apply {
        setPadding(0, dp(6), 0, dp(6))
    }

    private fun sectionHeader(title: String) = text(title.uppercase(Locale.ROOT), size = 13f, bold = true).apply {
        setTextColor(SECONDARY)
        setPadding(0, dp(24), 0, dp(4))
    }

    private fun caption(value: String) = text(value, size = 12f).apply {
        setTextColor(SECONDARY)
        setPadding(0, dp(4), 0, 0)
    }

    private fun stat() = text("0", size = 22f, bold = true)

    private fun monospace(value: String) = text(value, size = 13f, monospace = true, selectable = true)

    private fun text(value: String, size: Float = 15f, bold: Boolean = false, monospace: Boolean = false, selectable: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            typeface = Typeface.create(if (monospace) Typeface.MONOSPACE else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
            setTextIsSelectable(selectable)
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun time(millis: Long): String = DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(millis))

    public companion object {
        private const val EXTRA_SESSION_ID = "com.gu.davy.debug.SESSION_ID"
        private const val EXTRA_EVENT_ID = "com.gu.davy.debug.EVENT_ID"
        private const val EXTRA_REQUESTS = "com.gu.davy.debug.REQUESTS"
        private const val EXTRA_REQUEST_ID = "com.gu.davy.debug.REQUEST_ID"
        private const val EXTRA_FAILURE_REASON = "com.gu.davy.debug.FAILURE_REASON"
        private const val EXTRA_FAILURE_DATE = "com.gu.davy.debug.FAILURE_DATE"
        private const val MENU_SEARCH = 1
        private const val MENU_FLUSH = 2
        private const val MENU_CLEAR = 3
        private val SECONDARY = Color.rgb(0x75, 0x75, 0x75)
        private val ERROR = Color.rgb(0xD3, 0x2F, 0x2F)
        private val WARNING = Color.rgb(0xE6, 0x51, 0x00)
        private val SENT = Color.rgb(0x2E, 0x7D, 0x32)
        private val FULL_TIME = object : ThreadLocal<SimpleDateFormat>() {
            override fun initialValue() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
        }

        /** Instances shown by open screens, by session ID, so the screens can find them. */
        private val instances = mutableMapOf<String, WeakReference<Davy>>()

        private fun instance(sessionId: String): Davy? = synchronized(instances) { instances[sessionId]?.get() }

        /** An intent for a screen listing [telemetry]'s events, for example to start from a notification. */
        public fun intent(context: Context, telemetry: Davy): Intent {
            val sessionId = telemetry.sessionId.toString()
            synchronized(instances) {
                instances.values.removeAll { it.get() == null }
                instances[sessionId] = WeakReference(telemetry)
            }
            return Intent(context, DavyDebugActivity::class.java).putExtra(EXTRA_SESSION_ID, sessionId)
        }

        /** Starts a screen listing [telemetry]'s events. */
        public fun start(context: Context, telemetry: Davy) {
            val intent = intent(context, telemetry)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
