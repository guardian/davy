package com.gu.davy.example

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import com.gu.davy.DavyDebugActivity
import com.gu.davy.Event

class MainActivity : Activity() {
    private val telemetry get() = (application as ExampleApplication).telemetry

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // The identifier Davy sends with every event logged during this launch.
        findViewById<TextView>(R.id.session_id).text = telemetry.sessionId.toString().uppercase()

        findViewById<Button>(R.id.log_breadcrumb).setOnClickListener {
            telemetry.log(Event.Breadcrumb("button_tapped"))
        }
        findViewById<Switch>(R.id.notifications).setOnCheckedChangeListener { _, isChecked ->
            telemetry.log(Event.Breadcrumb(if (isChecked) "notifications_enabled" else "notifications_disabled"))
        }
        findViewById<Button>(R.id.log_error).setOnClickListener {
            telemetry.log(Event.Error("network_unavailable"))
        }
        findViewById<Button>(R.id.flush).setOnClickListener {
            telemetry.flush()
        }
        findViewById<Button>(R.id.debug_events).setOnClickListener {
            DavyDebugActivity.start(this, telemetry)
        }

        if (savedInstanceState == null) {
            telemetry.log(Event.Breadcrumb("content_viewed"))
        }
    }
}
