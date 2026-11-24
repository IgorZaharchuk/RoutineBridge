package com.routinebridge

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future

private enum class StatusType {
    LOADING,
    SUCCESS,
    ERROR,
    INFO
}

class MainActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    private val inputPersistence by lazy { RoutineInputPersistence(this) }
    private val repository by lazy { ManualRoutineRepository(applicationContext) }
    private var inputs = emptyList<RoutineInput>()
    private var routines = emptyList<ManualRoutine>()
    private lateinit var status: TextView
    private lateinit var inputContainer: LinearLayout
    private var shortcutPublishFailed = false
    private var shortcutPublishError: String? = null
    private var inputLoadError: String? = null
    private var refreshRequestId = 0
    private var refreshTask: Future<*>? = null
    private val refreshExecutor by lazy {
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "RoutineRefresh").apply { isDaemon = true }
        }
    }
    private fun actionTitle(action: RoutineInputAction) = when (action) {
        RoutineInputAction.ENABLE -> getString(R.string.action_enable)
        RoutineInputAction.DISABLE -> getString(R.string.action_disable)
        RoutineInputAction.TOGGLE -> getString(R.string.action_toggle)
    }

    private fun setStatus(type: StatusType, value: String) {
        status.text = value
        status.setTextColor(
            when (type) {
                StatusType.LOADING -> blue
                StatusType.SUCCESS -> Color.rgb(120, 200, 140)
                StatusType.ERROR -> Color.rgb(235, 130, 130)
                StatusType.INFO -> secondary
            }
        )
    }
    private var editingInputId: String? = null
    private val collapsedGroups = mutableSetOf<Long>()

    private val bg = Color.rgb(18, 18, 18)
    private val surface = Color.rgb(30, 30, 30)
    private val variant = Color.rgb(25, 31, 45)
    private val dialogVariant = Color.rgb(48, 64, 86)
    private val blue = Color.rgb(74, 144, 217)
    private val white = Color.WHITE
    private val secondary = Color.rgb(208, 214, 226)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.isStatusBarContrastEnforced = false
        window.isNavigationBarContrastEnforced = false
        window.decorView.systemUiVisibility = 0

        try {
            inputs = inputPersistence.load()
        } catch (e: Exception) {
            inputLoadError = e.message ?: e.javaClass.simpleName
            inputs = emptyList()
        }
        buildScreen()
        if (inputLoadError != null) {
            shortcutPublishFailed = true
        } else {
            val publication = inputPersistence.publishLoaded(inputs)
            shortcutPublishFailed = !publication.updated
            shortcutPublishError = publication.error
        }
        refreshRoutines()
        showShortcutError(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showShortcutError(intent)
    }

    private fun showShortcutError(intent: Intent?) {
        val message = intent?.getStringExtra(EXTRA_SHORTCUT_ERROR) ?: return
        intent.removeExtra(EXTRA_SHORTCUT_ERROR)
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.shortcut_execution_error))
            .setMessage(message)
            .setPositiveButton(getString(R.string.close), null)
            .show()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(bg)
        }
        root.setPadding(dp(20), dp(16), dp(20), dp(16))
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
            view.setPadding(dp(20) + bars.left, dp(16) + bars.top,
                dp(20) + bars.right, dp(16) + bars.bottom)
            insets
        }

        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }
        header.addView(
            text("RoutineBridge", 20f, white, true),
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        header.addView(
            button(getString(R.string.application_log_button)) {
                startActivity(Intent(this, ApplicationLogActivity::class.java))
            },
            LinearLayout.LayoutParams(dp(76), dp(40)).apply {
                marginEnd = dp(6)
            }
        )
        header.addView(
            button(getString(R.string.language_switch)) {
                getSharedPreferences("settings", MODE_PRIVATE)
                    .edit()
                    .putBoolean("english", !AppLocale.isEnglish(this))
                    .apply()
                recreate()
            },
            LinearLayout.LayoutParams(dp(76), dp(40))
        )
        content.addView(header, matchWrap().apply { bottomMargin = dp(4) })
        content.addView(text(getString(R.string.manual_routines_title), 14f, secondary)
            .also { it.setPadding(0, dp(4), 0, dp(8)) })

        status = text(getString(R.string.loading_samsung), 13f, blue)
        status.setPadding(dp(12), dp(12), dp(12), dp(12))
        status.background = rounded(variant, 10)
        content.addView(status, matchWrap().apply { bottomMargin = dp(10) })

        val addInputButton = button(getString(R.string.add_input)) { openEditor() }
        addInputButton.isEnabled = inputLoadError == null
        content.addView(
            addInputButton,
            matchWrap().apply { bottomMargin = dp(8) }
        )
        content.addView(button(getString(R.string.refresh_routines)) { refreshRoutines() }, matchWrap().apply { bottomMargin = dp(8) })
        content.addView(text(getString(R.string.my_inputs), 19f, white, true)
            .also { it.setPadding(0, dp(8), 0, dp(10)) })
        inputContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(inputContainer, matchWrap())

        val appVersion = packageManager.getPackageInfo(packageName, 0).versionName ?: "—"
        val github = text("github.com/IgorZaharchuk/RoutineBridge", 12f, blue).apply {
            setPadding(0, dp(24), 0, dp(4))
            isClickable = true
            setOnClickListener {
                startActivity(Intent(Intent.ACTION_VIEW,
                    android.net.Uri.parse("https://github.com/IgorZaharchuk/RoutineBridge")))
            }
        }
        content.addView(github)
        content.addView(text("${getString(R.string.version)} $appVersion", 12f, secondary))
        renderInputs()
        setContentView(root)
        root.requestApplyInsets()
    }

    private fun renderInputs() {
        RoutineInputCards.render(
            activity = this,
            container = inputContainer,
            inputs = inputs,
            routines = routines,
            collapsedGroups = collapsedGroups,
            emptyText = getString(R.string.empty_inputs),
            expandAllText = getString(R.string.expand_all),
            collapseAllText = getString(R.string.collapse_all),
            actionTitle = ::actionTitle,
            onInputClick = { input ->
                startActivity(Intent(this, ShortcutRunActivity::class.java).apply {
                    action = ACTION_RUN_INPUT
                    putExtra(EXTRA_INPUT_ID, input.id)
                })
            },
            onEditClick = { input ->
                editingInputId =
                    if (editingInputId == input.id) null else input.id
                renderInputs()
            },
            expandedContent = { input ->
                if (editingInputId == input.id) createInlineEditor(input) else null
            }
        )
    }

    private fun createInlineEditor(input: RoutineInput): View =
        RoutineInputEditor.create(
            activity = this,
            input = input,
            routines = routines,
            actionTitle = ::actionTitle,
            onSave = { updatedInput ->
                persist(inputs.map {
                    if (it.id == input.id) updatedInput else it
                })
            },
            onDelete = { confirmDelete(input) },
            onCancel = {
                editingInputId = null
                renderInputs()
            }
        )

    private fun refreshRoutines() {
        val requestId = ++refreshRequestId
        refreshTask?.cancel(true)
        setStatus(StatusType.LOADING, getString(R.string.loading_root_routines))
        refreshTask = refreshExecutor.submit {
            try {
                val loaded = repository.load()
                runOnUiThread {
                    if (requestId != refreshRequestId) return@runOnUiThread
                    routines = loaded
                    val loadedMessage = getString(R.string.routines_found, loaded.size)
                    when {
                        inputLoadError != null -> setStatus(
                            StatusType.ERROR,
                            getString(R.string.settings_load_error, inputLoadError)
                        )
                        shortcutPublishFailed -> setStatus(
                            StatusType.ERROR,
                            "$loadedMessage. ${shortcutPublishError ?: getString(R.string.shortcut_update_failed)}"
                        )
                        else -> setStatus(StatusType.SUCCESS, loadedMessage)
                    }
                    renderInputs()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (requestId != refreshRequestId) return@runOnUiThread
                    val message = when {
                        inputLoadError != null ->
                            getString(R.string.settings_load_error, inputLoadError)
                        e is RootCoreAccessException ->
                            getString(R.string.root_access_unavailable)
                        else ->
                            getString(R.string.loading_error, e.message ?: e.javaClass.simpleName)
                    }
                    setStatus(StatusType.ERROR, message)
                }
            }
        }
    }

    override fun onDestroy() {
        refreshRequestId++
        refreshTask?.cancel(true)
        refreshExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun openEditor() {
        if (routines.isEmpty()) {
            AlertDialog.Builder(this)
                .setMessage(getString(R.string.wait_for_routines))
                .setPositiveButton(getString(R.string.refresh)) { _, _ -> refreshRoutines() }
                .setNegativeButton(getString(R.string.close), null)
                .show()
            return
        }

        val availableRoutines = routines.toList()

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), 0)
        }
        val name = EditText(this).apply {
            hint = getString(R.string.input_name)
            setSingleLine(true)
        }
        form.addView(text(getString(R.string.input_name), 14f, secondary))
        form.addView(name, matchWrap())

        val routineSpinner = Spinner(this)
        val routineLabels = availableRoutines.map { it.name }
        routineSpinner.adapter = themedSpinnerAdapter(routineLabels)
        prepareSpinner(routineSpinner)
        form.addView(text(getString(R.string.manual_routine), 14f, secondary)
            .also { it.setPadding(0, dp(12), 0, 0) })
        form.addView(routineSpinner, matchWrap())

        val actionSpinner = Spinner(this)
        val actionValues = RoutineInputAction.entries
        actionSpinner.adapter = themedSpinnerAdapter(actionValues.map { actionTitle(it) })
        prepareSpinner(actionSpinner)
        actionSpinner.setSelection(2)
        form.addView(text(getString(R.string.action), 14f, secondary)
            .also { it.setPadding(0, dp(12), 0, 0) })
        form.addView(actionSpinner, matchWrap())

        val dialog = AlertDialog.Builder(this)
            .setTitle(getString(R.string.new_input))
            .setView(form)
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.save), null)
            .create()

        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(rounded(surface, 16))
            val cancelButton = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
            val saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            styleButton(cancelButton, dialogVariant)
            styleButton(saveButton, dialogVariant)
            cancelButton.minHeight = dp(48)
            saveButton.minHeight = dp(48)

            (cancelButton.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.height = dp(48)
                it.width = 0
                it.weight = 1f
                it.marginStart = dp(8)
                it.marginEnd = dp(6)
                cancelButton.layoutParams = it
            }
            (saveButton.layoutParams as? LinearLayout.LayoutParams)?.let {
                it.height = dp(48)
                it.width = 0
                it.weight = 1f
                it.marginStart = dp(6)
                it.marginEnd = dp(8)
                saveButton.layoutParams = it
            }

            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val label = name.text.toString().trim()
                if (label.isEmpty()) {
                    name.error = getString(R.string.enter_name)
                    return@setOnClickListener
                }
                val selectedRoutine =
                    availableRoutines.getOrNull(routineSpinner.selectedItemPosition)
                        ?: run {
                            setStatus(StatusType.ERROR, getString(R.string.wait_for_routines))
                            return@setOnClickListener
                        }
                val routine = routines.firstOrNull { it.uuid == selectedRoutine.uuid }
                    ?: run {
                        setStatus(
                            StatusType.ERROR,
                            getString(R.string.routine_not_found, selectedRoutine.name)
                        )
                        return@setOnClickListener
                    }
                val item = RoutineInput(
                    id = UUID.randomUUID().toString(),
                    label = label,
                    routineUuid = routine.uuid,
                    routineName = routine.name,
                    action = actionValues[actionSpinner.selectedItemPosition]
                )
                val updated = inputs.filterNot { it.id == item.id } + item
                if (persist(updated)) {
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun confirmDelete(input: RoutineInput) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_input_title))
            .setMessage(getString(R.string.delete_input_message))
            .setNegativeButton(getString(R.string.cancel), null)
            .setPositiveButton(getString(R.string.delete)) { _, _ ->
                persist(inputs.filterNot { it.id == input.id })
            }
            .show()
    }

    private fun persist(updated: List<RoutineInput>): Boolean {
        if (inputLoadError != null) {
            setStatus(StatusType.ERROR, getString(R.string.save_blocked))
            return false
        }
        return try {
            val result = inputPersistence.save(
                previous = inputs,
                updated = updated,
                disabledMessage = getString(R.string.shortcut_deleted)
            )
            inputs = updated

            val shortcutsUpdated = result.shortcutsUpdated
            shortcutPublishError = result.shortcutError
            shortcutPublishFailed = !shortcutsUpdated
            editingInputId = null
            if (shortcutsUpdated) {
                setStatus(StatusType.SUCCESS, getString(R.string.settings_saved, inputs.size))
            } else {
                setStatus(
                    StatusType.ERROR,
                    shortcutPublishError ?: getString(R.string.settings_saved_shortcut_error)
                )
            }
            renderInputs()
            true
        } catch (e: Exception) {
            val message = if (e is RootCoreAccessException) {
                getString(R.string.root_access_unavailable)
            } else {
                getString(R.string.save_error, e.message ?: e.javaClass.simpleName)
            }
            setStatus(StatusType.ERROR, message)
            false
        }
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun themedSpinnerAdapter(items: List<String>): ArrayAdapter<String> =
        object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            private fun makeTextView(convertView: View?, parent: ViewGroup,
                                     position: Int, dropdown: Boolean): TextView {
                val view = (convertView as? TextView) ?: TextView(this@MainActivity)
                view.text = getItem(position) ?: ""
                view.setTextColor(white)
                view.textSize = 14f
                view.gravity = android.view.Gravity.CENTER_VERTICAL
                view.setPadding(dp(12), dp(10), dp(12), dp(10))
                if (dropdown) view.setBackgroundColor(surface)
                return view
            }

            override fun getView(position: Int, convertView: View?, parent: ViewGroup) =
                makeTextView(convertView, parent, position, false)

            override fun getDropDownView(position: Int, convertView: View?,
                                         parent: ViewGroup) =
                makeTextView(convertView, parent, position, true)
        }.also {
            it.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }

    private fun prepareSpinner(spinner: Spinner) {
        spinner.setPopupBackgroundDrawable(rounded(surface, 8))
        spinner.backgroundTintList =
            android.content.res.ColorStateList.valueOf(dialogVariant)
    }

    private fun styleButton(button: Button, backgroundColor: Int = variant) {
        button.isAllCaps = false
        button.setTextColor(white)
        button.textSize = 13f
        button.typeface = Typeface.DEFAULT_BOLD
        button.gravity = android.view.Gravity.CENTER
        button.background = rounded(backgroundColor, 8)
        button.stateListAnimator = null
        button.elevation = 0f
        button.minimumWidth = 0
        button.setPadding(dp(4), 0, dp(4), 0)
    }

    private fun button(label: String, action: () -> Unit) =
        Button(this).apply {
            text = label
            styleButton(this)
            setOnClickListener { action() }
        }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)

    companion object {
        const val ACTION_RUN_INPUT = "com.routinebridge.action.RUN_INPUT"
        const val EXTRA_INPUT_ID = "routine_input_id"
        const val EXTRA_SHORTCUT_ERROR = "shortcut_error"
    }
}
