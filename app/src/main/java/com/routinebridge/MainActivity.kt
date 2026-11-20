package com.routinebridge

import android.app.Activity
import android.app.AlertDialog
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

class MainActivity : Activity() {
    private val store by lazy { RoutineInputStore(this) }
    private val repository by lazy { ManualRoutineRepository() }
    private var inputs = emptyList<RoutineInput>()
    private var routines = emptyList<ManualRoutine>()
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var inputContainer: LinearLayout
    private var english = false
    private var shortcutPublishFailed = false
    private var shortcutPublishError: String? = null
    private var inputLoadError: String? = null
    private var refreshRequestId = 0
    private val preferences by lazy {
        getSharedPreferences("settings", MODE_PRIVATE)
    }

    private fun tr(ru: String, en: String) = if (english) en else ru

    private fun actionTitle(action: RoutineInputAction) = when (action) {
        RoutineInputAction.ENABLE -> tr("Включить", "Enable")
        RoutineInputAction.DISABLE -> tr("Выключить", "Disable")
        RoutineInputAction.TOGGLE -> tr("Переключить", "Toggle")
    }

    private fun setStatus(value: String) {
        status.text = value
        status.setTextColor(when {
            value.contains("ошибка", true) ||
            value.contains("не удалось", true) ||
            value.contains("недоступен", true) ||
            value.contains("failed", true) ||
            value.contains("unavailable", true) ||
            value.contains("error", true) -> Color.rgb(235, 130, 130)
            value.contains("найдено", true) ||
            value.contains("сохранены", true) ||
            value.contains("успешно", true) ||
            value.contains("found", true) ||
            value.contains("saved", true) -> Color.rgb(120, 200, 140)
            value.contains("загрузка", true) ||
            value.contains("выполняю", true) ||
            value.contains("loading", true) ||
            value.contains("running", true) -> blue
            else -> secondary
        })
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
            inputs = store.load()
        } catch (e: Exception) {
            inputLoadError = e.message ?: e.javaClass.simpleName
            inputs = emptyList()
        }
        english = preferences.getBoolean("english", false)
        buildScreen()
        shortcutPublishFailed = if (inputLoadError != null) {
            true
        } else {
            shortcutLimitError(inputs)?.let {
                shortcutPublishError = it
                true
            } ?: runCatching {
                !RoutineShortcutPublisher(this).publishAll(inputs)
            }.getOrDefault(true)
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
            .setTitle(tr("Ошибка выполнения ярлыка", "Shortcut execution error"))
            .setMessage(message)
            .setPositiveButton(tr("Закрыть", "Close"), null)
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
        content = LinearLayout(this).apply {
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
            button(if (english) "Русский" else "English") {
                english = !english
                preferences.edit().putBoolean("english", english).apply()
                buildScreen()
                refreshRoutines()
            },
            LinearLayout.LayoutParams(-2, dp(40))
        )
        content.addView(header, matchWrap().apply { bottomMargin = dp(4) })
        content.addView(text(tr("Связь с ручными сценариями Samsung", "Samsung manual routines"), 14f, secondary)
            .also { it.setPadding(0, dp(4), 0, dp(8)) })

        status = text(tr("Загружаю сценарии Samsung…", "Loading Samsung routines…"), 13f, blue)
        status.setPadding(dp(12), dp(12), dp(12), dp(12))
        status.background = rounded(variant, 10)
        content.addView(status, matchWrap().apply { bottomMargin = dp(10) })

        val addInputButton = button(tr("＋  Добавить вход", "＋  Add input")) { openEditor() }
        addInputButton.isEnabled = inputLoadError == null
        content.addView(
            addInputButton,
            matchWrap().apply { bottomMargin = dp(8) }
        )
        content.addView(button(tr("Обновить список сценариев", "Refresh routines")) { refreshRoutines() }, matchWrap().apply { bottomMargin = dp(8) })

        content.addView(text(tr("Мои входы", "My inputs"), 19f, white, true)
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
        content.addView(text("${tr("Версия", "Version")} $appVersion", 12f, secondary))
        renderInputs()
        setContentView(root)
        root.requestApplyInsets()
    }

    private fun renderInputs() {
        inputContainer.removeAllViews()

        if (inputs.isEmpty()) {
            inputContainer.addView(text(
                tr("Пока нет входов.\nДобавь вход, выбери ручной сценарий Samsung и действие.", "No inputs yet.\nAdd an input, choose a Samsung manual routine and an action."),
                14f, secondary
            ).apply {
                setPadding(dp(14), dp(18), dp(14), dp(18))
                background = rounded(variant, 12)
            }, matchWrap())
            return
        }

        val groups = inputs.groupBy { it.routineUuid }
        val allCollapsed = groups.keys.all { it in collapsedGroups }

        inputContainer.addView(
            button(if (allCollapsed) tr("Развернуть все", "Expand all") else tr("Свернуть все", "Collapse all")) {
                if (allCollapsed) collapsedGroups.clear()
                else collapsedGroups.addAll(groups.keys)
                renderInputs()
            },
            matchWrap().apply { bottomMargin = dp(8) }
        )

        groups.forEach { (uuid, group) ->
            val groupBlock = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }

            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = rounded(variant, 12)
                isClickable = true
                isFocusable = true
            }

            header.addView(
                text(if (uuid in collapsedGroups) "›" else "⌄", 22f, blue, true)
                    .also { it.setPadding(0, 0, dp(12), 0) }
            )
            val currentRoutine = routines.firstOrNull { it.uuid == uuid }
            val routineName = currentRoutine?.name ?: group.first().routineName
            val displayedRoutineName = if (currentRoutine == null) {
                "$routineName${tr(" — не найден в Samsung", " — not found in Samsung")}"
            } else {
                routineName
            }

            header.addView(
                text(displayedRoutineName, 17f, white, true),
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            header.addView(
                text(if (english) "${group.size} input(s)" else "${group.size} вход(ов)", 12f, secondary)
            )

            header.setOnClickListener {
                if (!collapsedGroups.add(uuid)) collapsedGroups.remove(uuid)
                renderInputs()
            }
            groupBlock.addView(header, matchWrap())

            if (uuid !in collapsedGroups) {
                group.forEach { input ->
                    val card = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(14), dp(10), dp(14), dp(10))
                        background = rounded(surface, 12)
                    }

                    val row = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = android.view.Gravity.CENTER_VERTICAL
                    }
                    val details = LinearLayout(this).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    details.addView(text(input.label, 16f, white, true))
                    details.addView(
                        text(actionTitle(input.action), 13f, blue)
                            .also { it.setPadding(0, dp(4), 0, 0) }
                    )
                    row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))

                    val pencil = text("✎", 23f, blue, true).apply {
                        gravity = android.view.Gravity.CENTER
                        setPadding(dp(14), dp(4), dp(4), dp(4))
                        contentDescription = tr("Редактировать вход", "Edit input")
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            editingInputId =
                                if (editingInputId == input.id) null else input.id
                            renderInputs()
                        }
                    }
                    row.addView(pencil, LinearLayout.LayoutParams(-2, dp(44)))
                    card.addView(row, matchWrap())

                    if (editingInputId == input.id) {
                        card.addView(createInlineEditor(input), matchWrap())
                    }

                    groupBlock.addView(card, matchWrap().apply {
                        topMargin = dp(6)
                    })
                }
            }

            inputContainer.addView(groupBlock, matchWrap().apply {
                topMargin = dp(10)
            })
        }
    }

    private fun createInlineEditor(input: RoutineInput): View {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(4))
        }

        form.addView(text(tr("Название входа", "Input name"), 13f, secondary))
        val name = EditText(this).apply {
            setSingleLine(true)
            setText(input.label)
            setTextColor(white)
            setHintTextColor(secondary)
        }
        form.addView(name, matchWrap())

        val options = routines.toMutableList()
        if (options.none { it.uuid == input.routineUuid }) {
            options.add(0, ManualRoutine(input.routineUuid, input.routineName, null, null))
        }

        form.addView(
            text(tr("Ручной сценарий Samsung", "Samsung manual routine"), 13f, secondary)
                .also { it.setPadding(0, dp(10), 0, 0) }
        )
        val routineSpinner = Spinner(this)
        routineSpinner.adapter = themedSpinnerAdapter(options.map { routine ->
            if (routine.uuid == input.routineUuid &&
                routines.none { it.uuid == input.routineUuid }
            ) {
                "${routine.name}${tr(" — не найден в Samsung", " — not found in Samsung")}"
            } else {
                routine.name
            }
        })
        prepareSpinner(routineSpinner)
        routineSpinner.setSelection(
            options.indexOfFirst { it.uuid == input.routineUuid }.coerceAtLeast(0)
        )
        form.addView(routineSpinner, matchWrap())

        form.addView(
            text(tr("Действие", "Action"), 13f, secondary)
                .also { it.setPadding(0, dp(10), 0, 0) }
        )
        val actionValues = RoutineInputAction.entries
        val actionSpinner = Spinner(this)
        actionSpinner.adapter = themedSpinnerAdapter(actionValues.map { actionTitle(it) })
        prepareSpinner(actionSpinner)
        actionSpinner.setSelection(actionValues.indexOf(input.action).coerceAtLeast(0))
        form.addView(actionSpinner, matchWrap())

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        buttons.addView(dialogButton(tr("Удалить", "Delete")) {
            confirmDelete(input)
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            leftMargin = dp(4)
            rightMargin = dp(4)
        })

        buttons.addView(dialogButton(tr("Отмена", "Cancel")) {
            editingInputId = null
            renderInputs()
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            leftMargin = dp(4)
            rightMargin = dp(4)
        })

        buttons.addView(dialogButton(tr("Сохранить", "Save")) {
            val label = name.text.toString().trim()
            if (label.isEmpty()) {
                name.error = tr("Введи название", "Enter a name")
                return@dialogButton
            }

            val routine = options[routineSpinner.selectedItemPosition]
            val updatedInput = input.copy(
                label = label,
                routineUuid = routine.uuid,
                routineName = routine.name,
                action = actionValues[actionSpinner.selectedItemPosition]
            )
            persist(inputs.map {
                if (it.id == input.id) updatedInput else it
            })
        }, LinearLayout.LayoutParams(0, dp(48), 1f).apply {
            leftMargin = dp(4)
            rightMargin = dp(4)
        })

        form.addView(buttons, matchWrap())
        return form
    }

    private fun shortcutLimitError(items: List<RoutineInput>): String? {
        val limit = getSystemService(android.content.pm.ShortcutManager::class.java)
            .maxShortcutCountPerActivity
        if (items.size <= limit) return null
        return tr(
            "Не удалось создать ярлыки: лимит Android — $limit, настроено — ${items.size}.",
            "Failed to create shortcuts: Android limit is $limit, configured: ${items.size}."
        )
    }

    private fun refreshRoutines() {
        val requestId = ++refreshRequestId
        setStatus(tr("Загрузка ручных сценариев через Root…", "Loading routines through Root…"))
        Thread {
            try {
                val loaded = repository.load()
                runOnUiThread {
                    if (requestId != refreshRequestId) return@runOnUiThread
                    routines = loaded
                    val loadedMessage = tr(
                        "Найдено ручных сценариев: ${loaded.size}",
                        "Manual routines found: ${loaded.size}"
                    )
                    setStatus(
                        when {
                            inputLoadError != null -> tr(
                                "Ошибка загрузки настроек: $inputLoadError. Исходные данные сохранены.",
                                "Settings load error: $inputLoadError. Original data was preserved."
                            )
                            shortcutPublishFailed -> "$loadedMessage. ${shortcutPublishError ?: tr(
                                "Ошибка обновления ярлыков.",
                                "Shortcut update failed."
                            )}"
                            else -> loadedMessage
                        }
                    )
                    renderInputs()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    if (requestId != refreshRequestId) return@runOnUiThread
                    setStatus(
                        if (inputLoadError != null)
                            tr(
                                "Ошибка загрузки настроек: $inputLoadError. Исходные данные сохранены.",
                                "Settings load error: $inputLoadError. Original data was preserved."
                            )
                        else if (e is RootAccessException)
                            tr("Root-доступ недоступен.", "Root access unavailable.")
                        else
                            tr("Ошибка загрузки: ${e.message}", "Loading error: ${e.message}")
                    )
                }
            }
        }.start()
    }

    private fun openEditor() {
        if (routines.isEmpty()) {
            AlertDialog.Builder(this)
                .setMessage(tr("Сначала дождись загрузки ручных сценариев Samsung.", "Wait for Samsung manual routines to load first."))
                .setPositiveButton(tr("Обновить", "Refresh")) { _, _ -> refreshRoutines() }
                .setNegativeButton(tr("Закрыть", "Close"), null)
                .show()
            return
        }

        val availableRoutines = routines.toList()

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), 0)
        }
        val name = EditText(this).apply {
            hint = tr("Название входа", "Input name")
            setSingleLine(true)
        }
        form.addView(text(tr("Название входа", "Input name"), 14f, secondary))
        form.addView(name, matchWrap())

        val routineSpinner = Spinner(this)
        val routineLabels = availableRoutines.map { it.name }
        routineSpinner.adapter = themedSpinnerAdapter(routineLabels)
        prepareSpinner(routineSpinner)
        form.addView(text(tr("Ручной сценарий Samsung", "Samsung manual routine"), 14f, secondary)
            .also { it.setPadding(0, dp(12), 0, 0) })
        form.addView(routineSpinner, matchWrap())

        val actionSpinner = Spinner(this)
        val actionValues = RoutineInputAction.entries
        actionSpinner.adapter = themedSpinnerAdapter(actionValues.map { actionTitle(it) })
        prepareSpinner(actionSpinner)
        actionSpinner.setSelection(2)
        form.addView(text(tr("Действие", "Action"), 14f, secondary)
            .also { it.setPadding(0, dp(12), 0, 0) })
        form.addView(actionSpinner, matchWrap())

        val dialog = AlertDialog.Builder(this)
            .setTitle(tr("Новый вход", "New input"))
            .setView(form)
            .setNegativeButton(tr("Отмена", "Cancel"), null)
            .setPositiveButton(tr("Сохранить", "Save"), null)
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
                    name.error = tr("Введи название", "Enter a name")
                    return@setOnClickListener
                }
                val routine = availableRoutines[routineSpinner.selectedItemPosition]
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
            .setTitle(tr("Удалить вход?", "Delete input?"))
            .setMessage(tr("«${input.label}» и его ярлык будут удалены.", "“${input.label}” and its shortcut will be deleted."))
            .setNegativeButton(tr("Отмена", "Cancel"), null)
            .setPositiveButton(tr("Удалить", "Delete")) { _, _ ->
                persist(inputs.filterNot { it.id == input.id })
            }
            .show()
    }

    private fun persist(updated: List<RoutineInput>): Boolean {
        if (inputLoadError != null) {
            setStatus(tr(
                "Сохранение заблокировано: сначала нужно восстановить настройки. Исходные данные не изменены.",
                "Saving blocked: settings must be recovered first. Original data was not changed."
            ))
            return false
        }
        return try {
            val removedInputIds = inputs
                .filter { oldInput -> updated.none { it.id == oldInput.id } }
                .map { it.id }

            store.save(updated)
            inputs = updated

            val limitError = shortcutLimitError(updated)
            val shortcutsUpdated = if (limitError != null) {
                shortcutPublishError = limitError
                false
            } else {
                runCatching {
                    RoutineShortcutPublisher(this).publishAll(
                        updated,
                        removedInputIds,
                        tr(
                            "Этот вход удалён в RoutineBridge.",
                            "This input was deleted in RoutineBridge."
                        )
                    )
                }.getOrDefault(false)
            }

            if (shortcutsUpdated) shortcutPublishError = null
            shortcutPublishFailed = !shortcutsUpdated
            editingInputId = null
            setStatus(if (shortcutsUpdated) {
                tr(
                    "Настройки сохранены. Ярлыков: ${inputs.size}",
                    "Settings saved. Shortcuts: ${inputs.size}"
                )
            } else {
                shortcutPublishError ?: tr(
                    "Настройки сохранены, но ошибка обновления ярлыков.",
                    "Settings saved, but shortcut update failed."
                )
            })
            renderInputs()
            true
        } catch (e: Exception) {
            setStatus(
                if (e is RootAccessException)
                    tr("Root-доступ недоступен.", "Root access unavailable.")
                else
                    tr("Ошибка сохранения: ${e.message}", "Save error: ${e.message}")
            )
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

    private fun dialogButton(label: String, action: () -> Unit) =
        Button(this).apply {
            text = label
            styleButton(this, dialogVariant)
            minHeight = dp(48)
            setOnClickListener { action() }
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
