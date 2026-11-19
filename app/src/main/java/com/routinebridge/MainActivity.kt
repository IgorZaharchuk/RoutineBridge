package com.routinebridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var routinesOutput: TextView
    private lateinit var loadButton: Button
    private lateinit var shortcutNameInput: EditText
    private lateinit var shortcutStatus: TextView

    private val routineRepository by lazy {
        ManualRoutineRepository()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
        }

        val title = TextView(this).apply {
            text = "RoutineBridge"
            textSize = 26f
        }

        val subtitle = TextView(this).apply {
            text = "Связь с ручными сценариями Samsung"
            textSize = 14f
            setPadding(0, 4, 0, 24)
        }

        shortcutNameInput = EditText(this).apply {
            hint = "Название тестового ярлыка"
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(25))
        }

        val createShortcutButton = Button(this).apply {
            text = "Создать / обновить тестовый ярлык"
            setOnClickListener { createTestShortcut() }
        }

        shortcutStatus = TextView(this).apply {
            textSize = 13f
            text = "Тестовый ярлык ещё не создан."
            setPadding(0, 4, 0, 20)
        }

        val sectionTitle = TextView(this).apply {
            text = "Ручные сценарии Samsung"
            textSize = 18f
        }

        statusText = TextView(this).apply {
            textSize = 14f
            setPadding(0, 12, 0, 12)
            text = "Список ещё не загружен."
        }

        loadButton = Button(this).apply {
            text = "Загрузить сценарии"
            setOnClickListener { loadManualRoutines() }
        }

        routinesOutput = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
            text = ""
            setPadding(0, 16, 0, 16)
        }

        val scroll = ScrollView(this).apply {
            addView(
                routinesOutput,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        root.addView(title)
        root.addView(subtitle)
        root.addView(shortcutNameInput)
        root.addView(createShortcutButton)
        root.addView(shortcutStatus)
        root.addView(sectionTitle)
        root.addView(statusText)
        root.addView(loadButton)
        root.addView(scroll)

        setContentView(root)
        handleShortcutIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutIntent(intent)
    }

    private fun loadManualRoutines() {
        loadButton.isEnabled = false
        statusText.text = "Загрузка через Root..."
        routinesOutput.text = ""

        Thread {
            try {
                val routines = routineRepository.load()

                val display = if (routines.isEmpty()) {
                    "Ручные сценарии не найдены."
                } else {
                    routines.joinToString("\n\n") { routine ->
                        val state = when (routine.isRunning) {
                            true -> "Включён"
                            false -> "Выключен"
                            null -> "Состояние неизвестно"
                        }

                        val availability = when (routine.isEnabled) {
                            false -> " · отключён в Samsung"
                            null -> " · доступность неизвестна"
                            true -> ""
                        }

                        "${routine.name}\n" +
                            "$state$availability\n" +
                            "UUID: ${routine.uuid}"
                    }
                }

                runOnUiThread {
                    statusText.text =
                        "Найдено сценариев: ${routines.size}"
                    routinesOutput.text = display
                    loadButton.isEnabled = true
                }
            } catch (e: Exception) {
                runOnUiThread {
                    statusText.text = "Не удалось загрузить сценарии."
                    routinesOutput.text =
                        "${e.javaClass.simpleName}: ${e.message}"
                    loadButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun createTestShortcut() {
        val name = shortcutNameInput.text.toString().trim()
        if (name.isEmpty()) {
            shortcutStatus.text = "Сначала введи название ярлыка."
            return
        }

        try {
            val shortcutIntent = Intent(this, MainActivity::class.java).apply {
                action = ACTION_TEST_SHORTCUT
                putExtra(EXTRA_SHORTCUT_NAME, name)
                addFlags(
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }

            val published = RoutineShortcutPublisher(this).publish(
                SHORTCUT_ID,
                name,
                shortcutIntent
            )

            shortcutStatus.text = if (published) {
                "Ярлык опубликован: «$name»."
            } else {
                "Android не подтвердил публикацию ярлыка."
            }
        } catch (e: Exception) {
            shortcutStatus.text =
                "Ошибка создания ярлыка: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    private fun handleShortcutIntent(intent: Intent?) {
        if (intent?.action != ACTION_TEST_SHORTCUT) return

        val name = intent.getStringExtra(EXTRA_SHORTCUT_NAME)
            ?: "Без названия"

        shortcutStatus.text = "ЯРЛЫК СРАБОТАЛ! Название: $name"
    }

    companion object {
        private const val SHORTCUT_ID = "routinebridge_test_entry"
        private const val ACTION_TEST_SHORTCUT =
            "com.routinebridge.action.TEST_SHORTCUT"
        private const val EXTRA_SHORTCUT_NAME = "shortcut_name"
    }
}
