package com.routinebridge

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.routinebridge.rootcore.RootCore
import com.routinebridge.rootcore.RootCorePolicy
import com.routinebridge.rootcore.RootCoreSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class RootCoreDiagnosticsActivity : Activity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RootCoreDiagnostics").apply { isDaemon = true }
    }

    private var session: RootCoreSession? = null
    private lateinit var output: TextView

    private val providerUri = Uri.parse(
        "content://com.samsung.android.app.routines.externalprovider/" +
            "routine_list?routine_type=normal&condition_type=manual"
    )

    private val bg = Color.rgb(18, 18, 18)
    private val surface = Color.rgb(30, 30, 30)
    private val white = Color.WHITE
    private val secondary = Color.rgb(200, 205, 215)
    private val blue = Color.rgb(48, 64, 86)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(20))
            setBackgroundColor(bg)
        }

        content.addView(text(getString(R.string.diagnostics_title), 21f, white, true))
        content.addView(
            text(getString(R.string.diagnostics_intro), 14f, secondary)
        )

        val tests = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        addTestButton(tests, getString(R.string.diagnostics_open)) {
            runTest("OPEN SESSION") {
                check(session == null) { "Session is already open" }
                val start = SystemClock.elapsedRealtime()
                session = RootCoreAccess.open(applicationContext, 15_000L)
                "Binder connected; session=${session!!.sessionId.take(8)}; " +
                    "elapsed=${SystemClock.elapsedRealtime() - start} ms"
            }
        }

        addTestButton(tests, getString(R.string.diagnostics_ping)) {
            runTest("BINDER PING / UID") {
                val uid = requireSession().ping()
                check(uid == 0) { "Expected UID 0, received $uid" }
                "ping=$uid; root UID confirmed"
            }
        }

        addTestButton(tests, getString(R.string.diagnostics_provider_allow)) {
            runTest("PROVIDER ALLOW") {
                val result = requireSession().queryProvider(providerUri, null)
                val columns = result.getStringArray("columns")
                    ?: error("Provider returned no columns")
                @Suppress("DEPRECATION")
                val rows = result.getParcelableArrayList<Bundle>("rows")
                    ?: error("Provider returned no rows field")
                "Allowed query succeeded; columns=${columns.size}; rows=${rows.size}"
            }
        }

        addTestButton(tests, getString(R.string.diagnostics_provider_deny)) {
            runTest("PROVIDER DENY") {
                val denied = RootCore.open(
                    applicationContext,
                    RootCorePolicy.denyAll(),
                    15_000L
                )
                try {
                    val denial = try {
                        denied.queryProvider(providerUri, null)
                        null
                    } catch (error: SecurityException) {
                        error
                    }

                    check(
                        denial?.message?.contains(
                            "Provider authority is not allowed:"
                        ) == true
                    ) {
                        "Expected RootCore policy denial; got: " +
                            (denial?.message ?: "query unexpectedly succeeded")
                    }

                    "Provider access denied by RootCore policy: ${denial!!.message}"
                } finally {
                    denied.close()
                    check(denied.awaitTermination(5_000L)) {
                        "Denied RootCore process did not terminate"
                    }
                }
            }
        }

        addTestButton(tests, getString(R.string.diagnostics_repeat)) {
            runTest("FIVE QUERIES / ONE SESSION") {
                val current = requireSession()
                val reports = (1..5).map { index ->
                    val start = SystemClock.elapsedRealtime()
                    val result = current.queryProvider(providerUri, null)
                    val elapsed = SystemClock.elapsedRealtime() - start
                    @Suppress("DEPRECATION")
                    val rows = result.getParcelableArrayList<Bundle>("rows")
                        ?: error("Query $index returned no rows field")
                    "  #$index: ${elapsed} ms, rows=${rows.size}"
                }
                reports.joinToString("\n")
            }
        }

        addTestButton(tests, getString(R.string.diagnostics_activity_policy)) {
            runTest("ACTIVITY POLICY") {
                val component = ComponentName(
                    "com.samsung.android.app.routines",
                    "com.samsung.android.app.routines.ui.shortcut.ShortcutLaunchActivity"
                )
                val extraName = "EXTRA_KEY_ROUTINE_UUID"
                val flags = 0x10008000

                val policy = RootCorePolicy.builder()
                    .allowActivity(
                        component,
                        null,
                        flags,
                        mapOf(extraName to RootCorePolicy.ExtraType.LONG)
                    )
                    .build()

                val allowedIntent = Intent()
                    .setComponent(component)
                    .addFlags(flags)
                    .putExtra(extraName, 123L)

                val wrongTypeIntent = Intent()
                    .setComponent(component)
                    .addFlags(flags)
                    .putExtra(extraName, 123)

                val wrongComponentIntent = Intent()
                    .setComponent(
                        ComponentName(
                            "com.samsung.android.app.routines",
                            "com.samsung.android.app.routines.ui.shortcut.OtherActivity"
                        )
                    )
                    .addFlags(flags)
                    .putExtra(extraName, 123L)

                val allowed = policy.allowsActivity(allowedIntent)
                val wrongTypeRejected = !policy.allowsActivity(wrongTypeIntent)
                val wrongComponentRejected = !policy.allowsActivity(wrongComponentIntent)

                check(allowed) { "Expected Activity was rejected" }
                check(wrongTypeRejected) { "Wrong extra type was accepted" }
                check(wrongComponentRejected) { "Wrong component was accepted" }

                "Allowed component and LONG extra; wrong type and component rejected. " +
                    "No Activity was launched."
            }
        }

        addTestButton(tests, getString(R.string.diagnostics_independent)) {
            runTest("INDEPENDENT SESSIONS") {
                val first = RootCoreAccess.open(applicationContext, 15_000L)
                var second: RootCoreSession? = null
                try {
                    second = RootCoreAccess.open(applicationContext, 15_000L)

                    check(first.ping() == 0) { "First session ping failed" }
                    check(second.ping() == 0) { "Second session ping failed" }

                    first.close()
                    val firstStopped = first.awaitTermination(10_000L)
                    check(firstStopped) { "First root process did not terminate" }

                    check(second.isRootProcessAlive()) {
                        "Second process died after closing the first session"
                    }
                    check(second.ping() == 0) {
                        "Second session stopped responding"
                    }

                    "First session terminated; second session remained alive and responsive"
                } finally {
                    first.close()
                    second?.close()
                    runCatching { first.awaitTermination(2_000L) }
                    second?.let { runCatching { it.awaitTermination(2_000L) } }
                }
            }
        }

        addTestButton(tests, getString(R.string.diagnostics_close)) {
            runTest("CLOSE SESSION") {
                val current = requireSession()
                current.close()
                val terminated = current.awaitTermination(10_000L)
                check(terminated) {
                    "Root process is still alive: ${current.isRootProcessAlive()}"
                }
                session = null
                "Root process terminated; session closed"
            }
        }

        content.addView(tests)

        content.addView(
            text(getString(R.string.diagnostics_results), 16f, white, true).apply {
                setPadding(0, dp(18), 0, dp(8))
            }
        )

        output = text(getString(R.string.diagnostics_output_empty), 12f, secondary).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(surface, 10)
            setTextIsSelectable(true)
            gravity = Gravity.TOP
        }
        content.addView(output, LinearLayout.LayoutParams(-1, -2))

        val copyButton = Button(this).apply {
            text = getString(android.R.string.copy)
            isAllCaps = false
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        getString(R.string.diagnostics_title),
                        output.text
                    )
                )
            }
        }
        content.addView(
            copyButton,
            LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(6)
            }
        )

        addTestButton(content, getString(R.string.diagnostics_clear)) {
            output.text = getString(R.string.diagnostics_output_empty)
        }

        val scroll = ScrollView(this)
        scroll.addView(content)
        setContentView(scroll)
    }

    private fun requireSession(): RootCoreSession =
        session ?: error("Open a Root Core session first")

    private fun runTest(label: String, action: () -> String) {
        worker.execute {
            val start = SystemClock.elapsedRealtime()
            try {
                val result = action()
                appendLog(
                    "PASS  $label  (${SystemClock.elapsedRealtime() - start} ms)\n$result"
                )
            } catch (error: Throwable) {
                appendLog(
                    "FAIL  $label  (${SystemClock.elapsedRealtime() - start} ms)\n" +
                        "${error.javaClass.simpleName}: ${error.message ?: error}"
                )
            }
        }
    }

    private fun appendLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread {
            if (!::output.isInitialized || isFinishing || isDestroyed) return@runOnUiThread
            output.append("\n\n[$time] $message")
        }
    }

    private fun addTestButton(
        parent: LinearLayout,
        label: String,
        action: () -> Unit
    ) {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(white)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(blue, 9)
            stateListAnimator = null
            elevation = 0f
            minimumWidth = 0
            setPadding(dp(5), dp(4), dp(5), dp(4))
            setOnClickListener { action() }
        }
        parent.addView(
            button,
            LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(6)
            }
        )
    }

    private fun text(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(5), 0, dp(5))
    }

    private fun rounded(color: Int, radius: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        worker.execute {
            session?.let { current ->
                try {
                    current.close()
                    val terminated = current.awaitTermination(5_000L)
                    if (!terminated) {
                        android.util.Log.e(
                            "RootCoreDiagnostics",
                            "Root process did not terminate within 5000 ms"
                        )
                    }
                } catch (error: Throwable) {
                    android.util.Log.e(
                        "RootCoreDiagnostics",
                        "Failed to close RootCore session",
                        error
                    )
                } finally {
                    session = null
                }
            }
        }
        worker.shutdown()
        super.onDestroy()
    }
}
