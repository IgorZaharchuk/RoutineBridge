package com.routinebridge

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.net.Uri
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.routinebridge.rootcore.RootCoreSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID

class RootCoreDiagnosticsActivity : Activity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    private val worker = Executors.newSingleThreadExecutor()
    private var session: RootCoreSession? = null
    private lateinit var output: TextView
    private lateinit var journal: TextView
    private lateinit var routineSelection: LinearLayout
    private lateinit var inputContainer: LinearLayout
    private var availableRoutines: List<ManualRoutine> = emptyList()
    private val inputPersistence by lazy { RoutineInputPersistence(this) }
    private var diagnosticInputs: List<RoutineInput> = emptyList()
    private var editingDiagnosticInputId: String? = null
    private val selectedRoutineUuids = linkedSetOf<Long>()
    private val collapsedDiagnosticGroups = mutableSetOf<Long>()

    private val providerUri = Uri.parse(
        "content://com.samsung.android.app.routines.externalprovider/" +
            "routine_list?routine_type=normal&condition_type=manual"
    )

    private val bg = Color.rgb(18, 18, 18)
    private val white = Color.WHITE
    private val secondary = Color.rgb(200, 205, 215)
    private val blue = Color.rgb(55, 75, 105)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(20))
            setBackgroundColor(bg)
        }

        content.addView(text(getString(R.string.diagnostics_title), 21f, white))
        content.addView(text(
            getString(R.string.diagnostics_intro),
            14f, secondary
        ))

        addButton(content, getString(R.string.language_switch)) {
            getSharedPreferences("settings", MODE_PRIVATE)
                .edit()
                .putBoolean("english", !AppLocale.isEnglish(this))
                .apply()
            recreate()
        }

        val rootTests = collapsibleGroup(content, getString(R.string.diagnostics_root_group))

        addButton(rootTests, getString(R.string.diagnostics_test_1)) {
            runTest(getString(R.string.diagnostics_test_open_session)) {
                check(session == null) { getString(R.string.diagnostics_session_already_open) }
                val start = SystemClock.elapsedRealtime()
                session = RootCoreAccess.open(this, 30_000L)
                getString(R.string.diagnostics_binder_ready, SystemClock.elapsedRealtime() - start)
            }
        }

        addButton(rootTests, getString(R.string.diagnostics_test_2)) {
            runTest(getString(R.string.diagnostics_test_ping)) {
                val uid = requireSession().ping()
                check(uid == 0) { getString(R.string.diagnostics_expected_uid, uid) }
                getString(R.string.diagnostics_binder_response, uid)
            }
        }

        addButton(rootTests, getString(R.string.diagnostics_test_3)) {
            runTest(getString(R.string.diagnostics_test_first_provider)) {
                queryReport(requireSession())
            }
        }

        addButton(rootTests, getString(R.string.diagnostics_test_4)) {
            runTest(getString(R.string.diagnostics_test_repeated_queries)) {
                val current = requireSession()
                val results = (1..5).map { index ->
                    val start = SystemClock.elapsedRealtime()
                    val result = current.queryProvider(providerUri, null)
                    val elapsed = SystemClock.elapsedRealtime() - start
                    val rows = result.getParcelableArrayList<Bundle>("rows")
                    getString(R.string.diagnostics_repeated_query, index, elapsed, rows?.size ?: 0)
                }
                results.joinToString("\n")
            }
        }

        addButton(rootTests, getString(R.string.diagnostics_test_5)) {
            runTest(getString(R.string.diagnostics_test_close_session)) {
                val current = requireSession()
                current.close()
                val stopped = current.awaitTermination(5_000L)
                if (stopped) {
                    session = null
                    getString(R.string.diagnostics_root_stopped)
                } else {
                    getString(R.string.diagnostics_root_not_stopped, current.isRootProcessAlive())
                }
            }
        }

        val samsungTests = collapsibleGroup(content, getString(R.string.diagnostics_samsung_group))

        samsungTests.addView(text(
            getString(R.string.diagnostics_samsung_subtitle),
            17f, white
        ).apply { setPadding(0, dp(18), 0, dp(6)) })

        samsungTests.addView(text(
            getString(R.string.diagnostics_samsung_description),
            14f, secondary
        ))

        routineSelection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        samsungTests.addView(routineSelection, LinearLayout.LayoutParams(-1, -2))

        addButton(samsungTests, getString(R.string.refresh_routines)) {
            loadRoutines()
        }

        val inputTests = collapsibleGroup(content, getString(R.string.diagnostics_inputs_group))

        inputTests.addView(text(
            getString(R.string.diagnostics_inputs_subtitle),
            17f, white
        ).apply { setPadding(0, dp(22), 0, dp(6)) })

        inputTests.addView(text(
            getString(R.string.diagnostics_inputs_description),
            13f, secondary
        ))

        inputContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        inputTests.addView(inputContainer, LinearLayout.LayoutParams(-1, -2))

        addButton(samsungTests, getString(R.string.diagnostics_test_6), blue) {
            val selected = selectedRoutines(1)
            if (selected == null) {
                log(getString(R.string.diagnostics_select_one))
            } else {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.diagnostics_launch_title))
                    .setMessage(
                        getString(R.string.diagnostics_launch_message, selected.first().name)
                    )
                    .setNegativeButton(getString(R.string.cancel), null)
                    .setPositiveButton(getString(R.string.diagnostics_launch)) { _, _ ->
                        runTest(getString(R.string.diagnostics_single_launch)) {
                            runDispatcherSequence(selected, "TEST")
                        }
                    }
                    .show()
            }
        }

        addButton(samsungTests, getString(R.string.diagnostics_test_7), blue) {
            val selected = selectedRoutines(3)
            if (selected == null) {
                log(getString(R.string.diagnostics_select_three))
            } else {
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.diagnostics_sequence_title))
                    .setMessage(
                        getString(R.string.diagnostics_sequence_message)
                    )
                    .setNegativeButton(getString(R.string.cancel), null)
                    .setPositiveButton(getString(R.string.diagnostics_launch)) { _, _ ->
                        runTest(getString(R.string.diagnostics_sequence_launch)) {
                            runDispatcherSequence(selected, "T")
                        }
                    }
                    .show()
            }
        }

        output = text(getString(R.string.diagnostics_results_empty), 13f, secondary).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(30, 30, 30))
            setTextIsSelectable(true)
        }

        content.addView(output)

        addButton(content, getString(R.string.diagnostics_copy)) {
            val testReport = output.text.toString()
            worker.execute {
                val journalText = captureRoutineBridgeLogcat()
                val combined = testReport +
                    "\n\n===== LOGCAT: RoutineBridge =====\n" +
                    journalText

                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    journal.text = journalText

                    val clipboard =
                        getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(
                        ClipData.newPlainText("RoutineBridge diagnostics", combined)
                    )
                    Toast.makeText(
                        this,
                        getString(R.string.diagnostics_copied),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        addButton(content, getString(R.string.diagnostics_clear)) {
            output.text = getString(R.string.diagnostics_results_empty)
            journal.text = getString(R.string.diagnostics_journal_cleared)
        }

        content.addView(text(getString(R.string.diagnostics_journal_title), 16f, white))
        journal = text(getString(R.string.diagnostics_journal_unloaded), 12f, secondary).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(30, 30, 30))
            setTextIsSelectable(true)
        }
        content.addView(journal)

        val scroll = ScrollView(this)
        scroll.addView(content)
        setContentView(scroll)
        loadRoutines()
        loadDiagnosticInputs()
    }

    override fun onResume() {
        super.onResume()
        if (::journal.isInitialized) {
            refreshJournal()
        }
    }

    private fun refreshJournal() {
        worker.execute {
            val captured = captureRoutineBridgeLogcat()
            runOnUiThread {
                if (isFinishing || isDestroyed || !::journal.isInitialized) {
                    return@runOnUiThread
                }
                journal.text = captured
            }
        }
    }

    private fun loadRoutines() {
        worker.execute {
            try {
                val loaded = ManualRoutineRepository(applicationContext).load()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    availableRoutines = loaded
                    selectedRoutineUuids.retainAll(loaded.map { it.uuid }.toSet())
                    renderRoutineSelection()
                    if (::inputContainer.isInitialized) {
                        renderDiagnosticInputs()
                    }
                    log(getString(R.string.diagnostics_routines_loaded, loaded.size))
                }
            } catch (error: Throwable) {
                log(
                    getString(R.string.diagnostics_routines_load_error, error.javaClass.simpleName, error.message ?: error)
                )
            }
        }
    }

    private fun renderRoutineSelection() {
        if (!::routineSelection.isInitialized) return
        routineSelection.removeAllViews()

        val selected = selectedRoutineUuids.size
        routineSelection.addView(text(
            getString(R.string.diagnostics_selected_count, selected),
            12f, secondary
        ))

        availableRoutines.forEach { routine ->
            val isSelected = routine.uuid in selectedRoutineUuids
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                background = rounded(
                    if (isSelected) Color.rgb(48, 64, 86)
                    else Color.rgb(30, 30, 30), 12
                )
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (routine.uuid in selectedRoutineUuids) {
                        selectedRoutineUuids.remove(routine.uuid)
                    } else if (selectedRoutineUuids.size < 3) {
                        selectedRoutineUuids.add(routine.uuid)
                    } else {
                        Toast.makeText(
                            this@RootCoreDiagnosticsActivity,
                            getString(R.string.diagnostics_max_selected),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    renderRoutineSelection()
                }
            }
            card.addView(text(
                if (isSelected) "✓" else "○",
                20f, if (isSelected) Color.rgb(120, 200, 140) else secondary
            ).apply { setPadding(0, 0, dp(12), 0) })
            card.addView(text(
                routine.name, 15f, white
            ), LinearLayout.LayoutParams(0, -2, 1f))
            routineSelection.addView(card, LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(5)
            })
        }

        if (availableRoutines.isEmpty()) {
            routineSelection.addView(text(
                getString(R.string.diagnostics_no_routines),
                13f, secondary
            ))
        }
    }

    private fun selectedRoutines(count: Int): List<ManualRoutine>? {
        if (selectedRoutineUuids.size < count) return null
        val selected = selectedRoutineUuids.take(count).mapNotNull { uuid ->
            availableRoutines.firstOrNull { it.uuid == uuid }
        }
        if (selected.size != count) return null
        if (selected.map { it.uuid }.distinct().size != count) return null
        return selected
    }

    private fun loadDiagnosticInputs() {
        worker.execute {
            try {
                val loaded = inputPersistence.load()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    diagnosticInputs = loaded
                    renderDiagnosticInputs()
                    log(getString(R.string.diagnostics_inputs_loaded, loaded.size))
                }
            } catch (error: Throwable) {
                log(
                    getString(R.string.diagnostics_inputs_load_error, error.javaClass.simpleName, error.message ?: error)
                )
            }
        }
    }

    private fun renderDiagnosticInputs() {
        if (!::inputContainer.isInitialized) return
        RoutineInputCards.render(
            activity = this,
            container = inputContainer,
            inputs = diagnosticInputs,
            routines = availableRoutines,
            collapsedGroups = collapsedDiagnosticGroups,
            showInputCount = false,
            emptyText = getString(R.string.diagnostics_no_inputs),
            expandAllText = getString(R.string.expand_all),
            collapseAllText = getString(R.string.collapse_all),
            actionTitle = { action ->
                when (action) {
                    RoutineInputAction.ENABLE -> getString(R.string.action_enable)
                    RoutineInputAction.DISABLE -> getString(R.string.action_disable)
                    RoutineInputAction.TOGGLE -> getString(R.string.action_toggle)
                }
            },
            onInputClick = { input -> confirmDiagnosticInput(input) },
            onEditClick = { input ->
                editingDiagnosticInputId =
                    if (editingDiagnosticInputId == input.id) null else input.id
                renderDiagnosticInputs()
            },
            expandedContent = { input ->
                if (editingDiagnosticInputId == input.id) {
                    RoutineInputEditor.create(
                        activity = this,
                        input = input,
                        routines = availableRoutines,
                        actionTitle = { action ->
                            when (action) {
                                RoutineInputAction.ENABLE ->
                                    getString(R.string.action_enable)
                                RoutineInputAction.DISABLE ->
                                    getString(R.string.action_disable)
                                RoutineInputAction.TOGGLE ->
                                    getString(R.string.action_toggle)
                            }
                        },
                        onSave = { updated ->
                            saveDiagnosticInput(updated)
                        },
                        onDelete = {
                            AlertDialog.Builder(this)
                                .setTitle(getString(R.string.delete_input_title))
                                .setMessage(getString(R.string.delete_input_message))
                                .setNegativeButton(getString(R.string.cancel), null)
                                .setPositiveButton(getString(R.string.delete)) { _, _ ->
                                    saveDiagnosticInputs(
                                        diagnosticInputs.filterNot { it.id == input.id }
                                    )
                                }
                                .show()
                        },
                        onCancel = {
                            editingDiagnosticInputId = null
                            renderDiagnosticInputs()
                        }
                    )
                } else null
            }
        )
    }

    private fun saveDiagnosticInput(updated: RoutineInput) {
        saveDiagnosticInputs(
            diagnosticInputs.map {
                if (it.id == updated.id) updated else it
            }
        )
    }

    private fun saveDiagnosticInputs(updated: List<RoutineInput>) {
        try {
            val result = inputPersistence.save(
                previous = diagnosticInputs,
                updated = updated,
                disabledMessage = getString(R.string.shortcut_deleted)
            )

            diagnosticInputs = updated
            editingDiagnosticInputId = null
            renderDiagnosticInputs()

            if (result.shortcutsUpdated) {
                log(getString(R.string.diagnostics_inputs_saved, updated.size))
            } else {
                log(
                    result.shortcutError
                        ?: getString(R.string.diagnostics_shortcuts_update_unconfirmed)
                )
                AlertDialog.Builder(this)
                    .setTitle(getString(R.string.diagnostics_input_saved_title))
                    .setMessage(getString(R.string.diagnostics_shortcuts_update_unconfirmed_message))
                    .setPositiveButton(getString(R.string.close), null)
                    .show()
            }
        } catch (error: Throwable) {
            log(
                getString(R.string.diagnostics_input_save_error, error.javaClass.simpleName, error.message ?: error)
            )
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.diagnostics_operation_error_title))
                .setMessage(error.message ?: error.javaClass.simpleName)
                .setPositiveButton(getString(R.string.close), null)
                .show()
        }
    }

    private fun confirmDiagnosticInput(input: RoutineInput) {
        val requestId = UUID.randomUUID().toString()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.diagnostics_input_confirm_title))
            .setMessage(
                getString(
                    R.string.diagnostics_input_confirm_message,
                    input.label,
                    when (input.action) {
                        RoutineInputAction.ENABLE -> getString(R.string.action_enable)
                        RoutineInputAction.DISABLE -> getString(R.string.action_disable)
                        RoutineInputAction.TOGGLE -> getString(R.string.action_toggle)
                    },
                    input.routineName,
                    requestId
                )
            )
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.diagnostics_launch)) { _, _ ->
                log("INPUT_TEST_START request=$requestId inputId=${input.id}")
                val launch = Intent(
                    this,
                    ShortcutRunActivity::class.java
                ).apply {
                    action = MainActivity.ACTION_RUN_INPUT
                    putExtra(MainActivity.EXTRA_INPUT_ID, input.id)
                    putExtra(ShortcutRunActivity.EXTRA_REQUEST_ID, requestId)
                }
                try {
                    startActivity(launch)
                } catch (error: Throwable) {
                    log(
                        "INPUT_TEST_LAUNCH_FAILED request=$requestId " +
                            "${error.javaClass.simpleName}: ${error.message}"
                    )
                }
            }
            .show()
    }

    private fun runDispatcherSequence(
        selected: List<ManualRoutine>,
        label: String
    ): String {
        val report = mutableListOf<String>()
        val repository = ManualRoutineRepository(applicationContext)

        check(selected.isNotEmpty()) { getString(R.string.diagnostics_no_selected_routines) }
        check(selected.map { it.uuid }.distinct().size == selected.size) {
            getString(R.string.diagnostics_duplicate_routines)
        }

        report.add(getString(R.string.diagnostics_dispatcher_header))
        report.add(getString(R.string.diagnostics_dispatcher_mode))
        report.add(getString(R.string.diagnostics_dispatcher_selected, selected.joinToString { it.name }))

        val initialSession = RootCoreAccess.open(applicationContext)
        val initialRoutines = try {
            repository.load(initialSession)
        } finally {
            RootCoreAccess.close(initialSession)
        }

        val ready = selected.mapIndexed { index, chosen ->
            val current = initialRoutines.firstOrNull {
                it.uuid == chosen.uuid
            } ?: error(getString(R.string.diagnostics_routine_disappeared, label, index + 1))

            check(current.isEnabled == true) {
                getString(R.string.diagnostics_routine_disabled, label, index + 1, current.name)
            }

            val initialRunning = current.isRunning
            if (initialRunning == null) {
                error(
                    "${label}${index + 1}: UNKNOWN '${current.name}', " +
                        getString(R.string.diagnostics_provider_state_unknown)
                )
            }

            report.add(
                getString(R.string.diagnostics_routine_ready, label, index + 1, current.name, initialRunning)
            )
            current to initialRunning
        }

        val requests = ready.map { (routine, _) ->
            RoutineDispatchRequest(
                input = RoutineInput(
                    id = UUID.randomUUID().toString(),
                    label = "Root Core diagnostics",
                    routineUuid = routine.uuid,
                    routineName = routine.name,
                    action = RoutineInputAction.ENABLE
                ),
                requestId = UUID.randomUUID().toString()
            )
        }

        val latch = CountDownLatch(1)
        report.add("TEST_BATCH_ENQUEUE size=${requests.size}")

        SamsungRoutineDispatcher.enqueueBatch(
            context = applicationContext,
            requests = requests,
            onQueueDrained = { latch.countDown() }
        )

        requests.forEachIndexed { index, request ->
            report.add(
                getString(
                    R.string.diagnostics_routine_enqueued,
                    label,
                    index + 1,
                    request.input.routineUuid
                )
            )
        }

        check(latch.await(45, TimeUnit.SECONDS)) {
            getString(R.string.diagnostics_dispatcher_timeout, label, selected.size)
        }

        report.add("TEST_BATCH_DRAINED size=${requests.size}")

        val verificationSession = RootCoreAccess.open(applicationContext)
        val verifiedRoutines = try {
            repository.load(verificationSession)
        } finally {
            RootCoreAccess.close(verificationSession)
        }

        ready.forEachIndexed { index, (routine, initialRunning) ->
            val confirmed = verifiedRoutines.firstOrNull {
                it.uuid == routine.uuid
            } ?: error(getString(R.string.diagnostics_routine_disappeared_after, label, index + 1))

            check(confirmed.isRunning == true) {
                getString(R.string.diagnostics_launch_not_confirmed, label, index + 1, confirmed.isRunning)
            }

            val resultStatus = if (initialRunning) "SKIPPED" else "CONFIRMED"
            report.add(
                getString(R.string.diagnostics_routine_result, label, index + 1, resultStatus)
            )
        }

        report.add(getString(R.string.diagnostics_all_routines_confirmed))
        return report.joinToString("\n")
    }

    private fun queryReport(current: RootCoreSession): String {
        val start = SystemClock.elapsedRealtime()
        val result = current.queryProvider(providerUri, null)
        val elapsed = SystemClock.elapsedRealtime() - start

        val columns = result.getStringArray("columns") ?: emptyArray()
        @Suppress("DEPRECATION")
        val rows = result.getParcelableArrayList<Bundle>("rows") ?: arrayListOf()

        val lines = mutableListOf(
            getString(R.string.diagnostics_query_time, elapsed),
            getString(R.string.diagnostics_query_columns, columns.joinToString()),
            getString(R.string.diagnostics_query_rows, rows.size)
        )

        rows.take(10).forEachIndexed { index, row ->
            val values = columns.mapIndexed { columnIndex, column ->
                "$column=${row.getString("c$columnIndex")}"
            }
            lines.add("${index + 1}. ${values.joinToString(" | ")}")
        }

        if (rows.size > 10) {
            lines.add(getString(R.string.diagnostics_query_more_rows, rows.size))
        }

        return lines.joinToString("\n")
    }

    private fun requireSession(): RootCoreSession =
        session ?: throw IllegalStateException(getString(R.string.diagnostics_session_required))

    private fun runTest(label: String, action: () -> String) {
        worker.execute {
            val start = SystemClock.elapsedRealtime()
            try {
                val result = action()
                log(
                    getString(R.string.diagnostics_test_elapsed, label, SystemClock.elapsedRealtime() - start, result)
                )
            } catch (error: Throwable) {
                log(
                    getString(R.string.diagnostics_test_error, label, error.javaClass.simpleName, error.message ?: error)
                )
            }
        }
    }

    private fun log(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread {
            output.append("\n\n[$time] $message")
        }
    }

    private fun collapsibleGroup(
        parent: LinearLayout,
        title: String
    ): LinearLayout {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = android.view.View.GONE
            setPadding(0, dp(5), 0, dp(8))
        }

        var expanded = false
        val header = text("▸  $title", 15f, white).apply {
            setPadding(dp(14), dp(13), dp(14), dp(13))
            background = rounded(Color.rgb(38, 45, 58), 10)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                expanded = !expanded
                body.visibility = if (expanded) {
                    android.view.View.VISIBLE
                } else {
                    android.view.View.GONE
                }
                text = (if (expanded) "▾  " else "▸  ") + title
            }
        }

        parent.addView(
            header,
            LinearLayout.LayoutParams(-1, -2).apply {
                topMargin = dp(12)
            }
        )
        parent.addView(body, LinearLayout.LayoutParams(-1, -2))
        return body
    }

    private fun addButton(
        parent: LinearLayout,
        label: String,
        backgroundColor: Int = Color.rgb(25, 31, 45),
        action: () -> Unit
    ) {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(white)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(backgroundColor, 8)
            stateListAnimator = null
            elevation = 0f
            minimumWidth = 0
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener { action() }
        }
        parent.addView(
            button,
            LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(7)
                bottomMargin = dp(3)
            }
        )
    }

    private fun captureRoutineBridgeLogcat(): String {
        return try {
            val process = ProcessBuilder(
                "logcat", "-d", "-t", "1500", "-v", "time",
                "-s", "RoutineBridge:I"
            ).redirectErrorStream(true).start()

            val captured = process.inputStream.bufferedReader().use { it.readText() }
            val finished = process.waitFor()
            if (finished != 0) {
                getString(R.string.diagnostics_logcat_exit_error, finished, captured)
            } else if (captured.isBlank()) {
                getString(R.string.diagnostics_logcat_empty)
            } else {
                captured
            }
        } catch (error: SecurityException) {
            getString(R.string.diagnostics_logcat_denied, error.message ?: error)
        } catch (error: Throwable) {
            getString(R.string.diagnostics_logcat_error, error.javaClass.simpleName, error.message ?: error)
        }
    }

    private fun rounded(color: Int, radius: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun text(value: String, size: Float, color: Int) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(7), 0, dp(7))
        }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        worker.execute {
            val current = session
            if (current != null) {
                current.close()
                runCatching { current.awaitTermination(2_000L) }
                session = null
            }
        }
        worker.shutdown()
        super.onDestroy()
    }
}
