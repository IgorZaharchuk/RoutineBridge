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
import android.util.Log
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
    private lateinit var addButton: Button
    private lateinit var inputContainer: LinearLayout
    private var busy = false
    private var editingInputId: String? = null
    private val collapsedGroups = mutableSetOf<Long>()

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
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        window.decorView.systemUiVisibility = 0

        inputs = store.load()
        buildScreen()
        handleShortcutIntent(intent)
        refreshRoutines(false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutIntent(intent)
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

        val title = text("RoutineBridge", 28f, white, true)
        content.addView(title)
        content.addView(text("Связь с ручными сценариями Samsung", 14f, secondary)
            .also { it.setPadding(0, dp(4), 0, dp(22)) })

        status = text("Загружаю сценарии Samsung…", 13f, secondary)
        content.addView(status)

        addButton = button("＋  Добавить вход") { openEditor(null) }
        content.addView(addButton, matchWrap())
        content.addView(button("Обновить список сценариев") { refreshRoutines(true) }, matchWrap())

        content.addView(text("Мои входы", 19f, white, true)
            .also { it.setPadding(0, dp(22), 0, dp(10)) })
        inputContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(inputContainer, matchWrap())
        renderInputs()
        setContentView(root)
        root.requestApplyInsets()
    }

    private fun renderInputs() {
        inputContainer.removeAllViews()

        if (inputs.isEmpty()) {
            inputContainer.addView(text(
                "Пока нет входов.\nДобавь вход, выбери ручной сценарий Samsung и действие.",
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
            button(if (allCollapsed) "Развернуть все" else "Свернуть все") {
                if (allCollapsed) collapsedGroups.clear()
                else collapsedGroups.addAll(groups.keys)
                renderInputs()
            },
            matchWrap()
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
            header.addView(
                text(group.first().routineName, 17f, white, true),
                LinearLayout.LayoutParams(0, -2, 1f)
            )
            header.addView(
                text("${group.size} вход(ов)", 12f, secondary)
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
                        text(input.action.title, 13f, blue)
                            .also { it.setPadding(0, dp(4), 0, 0) }
                    )
                    row.addView(details, LinearLayout.LayoutParams(0, -2, 1f))

                    val pencil = text("✎", 23f, blue, true).apply {
                        gravity = android.view.Gravity.CENTER
                        setPadding(dp(14), dp(4), dp(4), dp(4))
                        contentDescription = "Редактировать вход"
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

        form.addView(text("Название входа", 13f, secondary))
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
            text("Ручной сценарий Samsung", 13f, secondary)
                .also { it.setPadding(0, dp(10), 0, 0) }
        )
        val routineSpinner = Spinner(this)
        routineSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            options.map { it.name }
        )
        routineSpinner.setSelection(
            options.indexOfFirst { it.uuid == input.routineUuid }.coerceAtLeast(0)
        )
        form.addView(routineSpinner, matchWrap())

        form.addView(
            text("Действие", 13f, secondary)
                .also { it.setPadding(0, dp(10), 0, 0) }
        )
        val actionValues = RoutineInputAction.entries
        val actionSpinner = Spinner(this)
        actionSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            actionValues.map { it.title }
        )
        actionSpinner.setSelection(actionValues.indexOf(input.action).coerceAtLeast(0))
        form.addView(actionSpinner, matchWrap())

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        buttons.addView(button("Удалить") {
            confirmDelete(input)
        }, LinearLayout.LayoutParams(0, dp(48), 1f))

        buttons.addView(button("Отмена") {
            editingInputId = null
            renderInputs()
        }, LinearLayout.LayoutParams(0, dp(48), 1f))

        buttons.addView(button("Сохранить") {
            val label = name.text.toString().trim()
            if (label.isEmpty()) {
                name.error = "Введи название"
                return@button
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
        }, LinearLayout.LayoutParams(0, dp(48), 1f))

        form.addView(buttons, matchWrap())
        return form
    }

    private fun refreshRoutines(showMessage: Boolean) {
        status.text = "Загрузка ручных сценариев через Root…"
        Thread {
            try {
                val loaded = repository.load()
                runOnUiThread {
                    routines = loaded
                    status.text = "Найдено ручных сценариев: ${loaded.size}"
                    if (showMessage) renderInputs()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    status.text = "Ошибка загрузки: ${e.message}"
                }
            }
        }.start()
    }

    private fun openEditor(existing: RoutineInput?) {
        if (routines.isEmpty()) {
            AlertDialog.Builder(this)
                .setMessage("Сначала дождись загрузки ручных сценариев Samsung.")
                .setPositiveButton("Обновить") { _, _ -> refreshRoutines(true) }
                .setNegativeButton("Закрыть", null)
                .show()
            return
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(12), dp(22), 0)
        }
        val name = EditText(this).apply {
            hint = "Название входа"
            setSingleLine(true)
            setText(existing?.label ?: "")
        }
        form.addView(text("Название входа", 14f, secondary))
        form.addView(name, matchWrap())

        val routineSpinner = Spinner(this)
        val routineLabels = routines.map { it.name }
        routineSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, routineLabels
        )
        val selectedRoutine = routines.indexOfFirst { it.uuid == existing?.routineUuid }
        if (selectedRoutine >= 0) routineSpinner.setSelection(selectedRoutine)
        form.addView(text("Ручной сценарий Samsung", 14f, secondary)
            .also { it.setPadding(0, dp(12), 0, 0) })
        form.addView(routineSpinner, matchWrap())

        val actionSpinner = Spinner(this)
        val actionValues = RoutineInputAction.entries
        actionSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item,
            actionValues.map { it.title }
        )
        actionSpinner.setSelection(actionValues.indexOf(existing?.action)
            .takeIf { it >= 0 } ?: 2)
        form.addView(text("Действие", 14f, secondary)
            .also { it.setPadding(0, dp(12), 0, 0) })
        form.addView(actionSpinner, matchWrap())

        val dialog = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Новый вход" else "Изменить вход")
            .setView(form)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить", null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val label = name.text.toString().trim()
                if (label.isEmpty()) {
                    name.error = "Введи название"
                    return@setOnClickListener
                }
                val routine = routines[routineSpinner.selectedItemPosition]
                val item = RoutineInput(
                    id = existing?.id ?: UUID.randomUUID().toString(),
                    label = label,
                    routineUuid = routine.uuid,
                    routineName = routine.name,
                    action = actionValues[actionSpinner.selectedItemPosition]
                )
                val updated = inputs.filterNot { it.id == item.id } + item
                persist(updated)
                dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun confirmDelete(input: RoutineInput) {
        AlertDialog.Builder(this)
            .setTitle("Удалить вход?")
            .setMessage("«${input.label}» и его ярлык будут удалены.")
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Удалить") { _, _ ->
                persist(inputs.filterNot { it.id == input.id })
            }
            .show()
    }

    private fun persist(updated: List<RoutineInput>) {
        try {
            store.save(updated)
            RoutineShortcutPublisher(this).publishAll(updated)
            inputs = updated
            editingInputId = null
            status.text = "Настройки сохранены. Ярлыков: ${inputs.size}"
            renderInputs()
        } catch (e: Exception) {
            status.text = "Ошибка сохранения: ${e.message}"
        }
    }

    private fun handleShortcutIntent(intent: Intent?) {
        if (intent?.action != ACTION_RUN_INPUT) return
        val id = intent.getStringExtra(EXTRA_INPUT_ID) ?: return
        val requestId = UUID.randomUUID().toString().take(8)
        val source = if (Build.VERSION.SDK_INT >= 22) referrer?.toString() else null

        Log.i(LOG_TAG, "SHORTCUT_RECEIVED request=$requestId input=$id busy=$busy referrer=$source flags=${intent.flags}")

        if (busy) {
            Log.w(LOG_TAG, "SHORTCUT_IGNORED request=$requestId reason=busy")
            return
        }

        val input = inputs.firstOrNull { it.id == id } ?: run {
            Log.e(LOG_TAG, "SHORTCUT_ERROR request=$requestId reason=input_not_found")
            status.text = "Вход не найден. Открой приложение и обнови ярлыки."
            return
        }

        busy = true
        Log.i(LOG_TAG, "SHORTCUT_ACCEPTED request=$requestId label=${input.label} action=${input.action} routineUuid=${input.routineUuid}")
        status.text = "Выполняю: ${input.label}…"

        Thread {
            try {
                val result = RoutineActionExecutor(repository).execute(input, requestId)
                Log.i(LOG_TAG, "SHORTCUT_FINISHED request=$requestId result=$result")
                runOnUiThread { status.text = result; busy = false }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "SHORTCUT_FAILED request=$requestId error=${e.message}", e)
                runOnUiThread {
                    status.text = "Не удалось выполнить «${input.label}»: ${e.message}"
                    busy = false
                }
            }
        }.start()
    }

    private fun text(value: String, size: Float, color: Int, bold: Boolean = false) =
        TextView(this).apply {
            text = value
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun button(label: String, action: () -> Unit) =
        Button(this).apply {
            text = label
            setTextColor(white)
            setOnClickListener { action() }
        }

    private fun rounded(color: Int, radius: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun matchWrap() = LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT)

    companion object {
        private const val LOG_TAG = "RoutineBridge"
        const val ACTION_RUN_INPUT = "com.routinebridge.action.RUN_INPUT"
        const val EXTRA_INPUT_ID = "routine_input_id"
    }
}
