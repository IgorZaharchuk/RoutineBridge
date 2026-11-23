package com.routinebridge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class ApplicationLogActivity : Activity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    private val logExecutor = Executors.newSingleThreadExecutor()
    private var loadGeneration = 0
    private var journalText = ""

    private lateinit var journal: TextView

    private val bg = Color.rgb(18, 18, 18)
    private val surface = Color.rgb(30, 30, 30)
    private val variant = Color.rgb(25, 31, 45)
    private val blue = Color.rgb(74, 144, 217)
    private val white = Color.WHITE
    private val secondary = Color.rgb(208, 214, 226)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = bg
        window.navigationBarColor = bg

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
            setPadding(dp(20), dp(16), dp(20), dp(16))
        }

        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars())
            } else {
                @Suppress("DEPRECATION")
                android.graphics.Insets.of(
                    insets.systemWindowInsetLeft,
                    insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight,
                    insets.systemWindowInsetBottom
                )
            }
            view.setPadding(
                dp(20) + bars.left,
                dp(16) + bars.top,
                dp(20) + bars.right,
                dp(16) + bars.bottom
            )
            insets
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(
            text("RoutineBridge", 20f, white, true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        root.addView(header, matchWrap())

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        actions.addView(
            button(getString(R.string.application_log_refresh)) {
                loadJournal()
            },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginEnd = dp(6)
            }
        )

        actions.addView(
            button(getString(R.string.application_log_copy)) {
                copyJournal()
            },
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(6)
            }
        )

        root.addView(
            actions,
            LinearLayout.LayoutParams(-1, dp(48)).apply {
                bottomMargin = dp(10)
            }
        )

        val logScroll = ScrollView(this).apply {
            isFillViewport = true
            background = rounded(surface, 10)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }

        journal = text(
            getString(R.string.application_log_loading),
            12f,
            secondary
        ).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            gravity = Gravity.TOP
        }

        logScroll.addView(journal)
        root.addView(logScroll, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
        root.requestApplyInsets()
    }

    override fun onResume() {
        super.onResume()
        loadJournal()
    }

    private fun loadJournal() {
        val generation = ++loadGeneration
        journal.text = getString(R.string.application_log_loading)

        logExecutor.execute {
            val captured = captureLogcat()

            runOnUiThread {
                if (isFinishing || isDestroyed || generation != loadGeneration) {
                    return@runOnUiThread
                }

                journalText = captured
                journal.text = captured
            }
        }
    }

    private fun captureLogcat(): String {
        return try {
            val process = ProcessBuilder(
                "logcat",
                "-d",
                "-t", "1500",
                "-v", "time",
                "-s",
                "RoutineBridge:V",
                "RoutineBridgeRootCore:V"
            ).redirectErrorStream(true).start()

            val captured = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor()

            when {
                finished != 0 -> getString(
                    R.string.application_log_exit_error,
                    finished,
                    captured
                )
                captured.isBlank() -> getString(R.string.application_log_empty)
                else -> captured
            }
        } catch (error: SecurityException) {
            getString(
                R.string.application_log_error,
                error.javaClass.simpleName,
                error.message ?: error
            )
        } catch (error: Throwable) {
            getString(
                R.string.application_log_error,
                error.javaClass.simpleName,
                error.message ?: error
            )
        }
    }

    private fun copyJournal() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                getString(R.string.application_log_title),
                journalText
            )
        )
        Toast.makeText(
            this,
            getString(R.string.application_log_copied),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun button(label: String, action: () -> Unit) =
        Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(white)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(variant, 8)
            stateListAnimator = null
            elevation = 0f
            minimumWidth = 0
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { action() }
        }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
            if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, dp(7), 0, dp(7))
        }

    private fun rounded(color: Int, radius: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()

    private fun matchWrap() =
        LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)

    override fun onDestroy() {
        loadGeneration++
        logExecutor.shutdownNow()
        super.onDestroy()
    }
}
