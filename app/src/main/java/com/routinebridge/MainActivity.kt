package com.routinebridge

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private val routinesPackage = "com.samsung.android.app.routines"
    private val authority = "com.samsung.android.app.routines.externalprovider"

    private lateinit var output: TextView
    private var lastReport = ""
    private lateinit var shortcutNameInput: EditText
    private lateinit var shortcutStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }

        output = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
            text = "RoutineBridge 0.1.0\n\nНажми «Проверить систему»."
        }

        val scroll = ScrollView(this).apply {
            addView(output)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        }

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val copyButton = Button(this).apply {
            text = "Копировать"
            setOnClickListener { copyReport() }
        }

        val saveButton = Button(this).apply {
            text = "Сохранить в файл"
            setOnClickListener { saveReport() }
        }

        actions.addView(copyButton, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(saveButton, LinearLayout.LayoutParams(0,
            LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val checkButton = Button(this).apply {
            text = "Проверить систему"
            setOnClickListener {
                lastReport = diagnose()
                output.text = lastReport
            }
        }

        val shortcutHeader = TextView(this).apply {
            text = "ТЕСТ ДИНАМИЧЕСКОГО ЯРЛЫКА"
            textSize = 16f
        }

        shortcutNameInput = EditText(this).apply {
            hint = "Название активности"
            setSingleLine(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(25))
        }

        val createShortcutButton = Button(this).apply {
            text = "Создать / обновить ярлык"
            setOnClickListener { createTestShortcut() }
        }

        shortcutStatus = TextView(this).apply {
            textSize = 14f
            text = "Ярлык ещё не создан и не запускался."
            setPadding(0, 8, 0, 16)
        }

        root.addView(shortcutHeader)
        root.addView(shortcutNameInput)
        root.addView(createShortcutButton)
        root.addView(shortcutStatus)
        root.addView(scroll)
        root.addView(actions)
        root.addView(checkButton)

        setContentView(root)
        handleShortcutIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShortcutIntent(intent)
    }

    private fun createTestShortcut() {
        val name = shortcutNameInput.text.toString().trim()
        if (name.isEmpty()) {
            shortcutStatus.text = "Сначала введи название ярлыка."
            return
        }

        try {
            val manager = getSystemService(
                android.content.pm.ShortcutManager::class.java
            )

            val shortcutIntent = Intent(this, MainActivity::class.java).apply {
                action = ACTION_TEST_SHORTCUT
                putExtra(EXTRA_SHORTCUT_NAME, name)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }

            val shortcut = android.content.pm.ShortcutInfo.Builder(
                this,
                SHORTCUT_ID
            )
                .setShortLabel(name)
                .setLongLabel(name)
                .setIcon(
                    android.graphics.drawable.Icon.createWithResource(
                        this,
                        android.R.drawable.ic_menu_view
                    )
                )
                .setIntent(shortcutIntent)
                .build()

            val added = manager.addDynamicShortcuts(listOf(shortcut))

            shortcutStatus.text = if (added) {
                "Ярлык опубликован: «$name».\n" +
                    "Теперь проверь, виден ли он в Samsung Routines."
            } else {
                "Android не подтвердил публикацию ярлыка."
            }
        } catch (e: Exception) {
            shortcutStatus.text =
                "Ошибка создания ярлыка: ${e.javaClass.simpleName}\n${e.message}"
        }
    }

    private fun handleShortcutIntent(intent: Intent?) {
        if (intent?.action != ACTION_TEST_SHORTCUT) return

        val name = intent.getStringExtra(EXTRA_SHORTCUT_NAME) ?: "Без названия"
        val time = java.text.SimpleDateFormat(
            "HH:mm:ss",
            java.util.Locale.getDefault()
        ).format(java.util.Date())

        shortcutStatus.text =
            "ЯРЛЫК СРАБОТАЛ!\nНазвание: $name\nВремя: $time"
    }

    private fun copyReport() {
        if (lastReport.isEmpty()) {
            Toast.makeText(this, "Сначала запусти проверку", Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("RoutineBridge report", lastReport))
        Toast.makeText(this, "Отчёт скопирован", Toast.LENGTH_SHORT).show()
    }

    private fun saveReport() {
        if (lastReport.isEmpty()) {
            Toast.makeText(this, "Сначала запусти проверку", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "text/plain"
            putExtra(Intent.EXTRA_TITLE, "RoutineBridge-report.txt")
        }

        startActivityForResult(intent, 1001)
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == 1001 && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            try {
                contentResolver.openOutputStream(uri)?.use {
                    it.write(lastReport.toByteArray(Charsets.UTF_8))
                }
                Toast.makeText(this, "Отчёт сохранён", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    companion object {
        private const val SHORTCUT_ID = "routinebridge_test_entry"
        private const val ACTION_TEST_SHORTCUT =
            "com.routinebridge.action.TEST_SHORTCUT"
        private const val EXTRA_SHORTCUT_NAME = "shortcut_name"
    }

    private fun diagnose(): String {
        val result = StringBuilder()
        result.appendLine("RoutineBridge 0.1.0")
        result.appendLine("Диагностика Samsung Modes and Routines")
        result.appendLine()

        try {
            val info = packageManager.getPackageInfo(
                routinesPackage,
                PackageManager.GET_ACTIVITIES or
                    PackageManager.GET_PROVIDERS or
                    PackageManager.GET_PERMISSIONS
            )

            result.appendLine("ПАКЕТ: найден")
            result.appendLine("Версия: ${info.versionName}")
            result.appendLine("Имя: ${info.packageName}")
            result.appendLine()

            result.appendLine("ACTIVITY:")
            val activities = info.activities.orEmpty()
            if (activities.isEmpty()) {
                result.appendLine("Список не получен")
            } else {
                activities.forEach {
                    result.appendLine(it.name)
                    result.appendLine("  exported=${it.exported}")
                }
            }

            result.appendLine()
            result.appendLine("CONTENT PROVIDERS:")
            val providers = info.providers.orEmpty()
            if (providers.isEmpty()) {
                result.appendLine("Список не получен")
            } else {
                providers.forEach {
                    result.appendLine(it.name)
                    result.appendLine("  authority=${it.authority}")
                    result.appendLine("  exported=${it.exported}")
                    result.appendLine("  readPermission=${it.readPermission}")
                    result.appendLine("  writePermission=${it.writePermission}")
                }
            }

            result.appendLine()
            result.appendLine("РАЗРЕШЕНИЯ ПАКЕТА:")
            info.requestedPermissions.orEmpty().forEach {
                result.appendLine(it)
            }

            result.appendLine()
            result.appendLine("ПРОВЕРКА ДОСТУПА:")
            val permission =
                "com.samsung.android.app.routines.permission.READ_ROUTINE_INFO"
            val granted = checkSelfPermission(permission) ==
                PackageManager.PERMISSION_GRANTED
            result.appendLine("READ_ROUTINE_INFO granted=$granted")

            val modeInfoPermission =
                "com.samsung.android.app.routines.permission.READ_MODE_INFO"
            val modeInfoGranted = checkSelfPermission(modeInfoPermission) ==
                PackageManager.PERMISSION_GRANTED

            result.appendLine("READ_MODE_INFO granted=$modeInfoGranted")

            result.appendLine()
            result.appendLine("ПРЯМОЙ ВЫЗОВ MODE INFO PROVIDER:")

            try {
                val modeInfoUri = Uri.parse(
                    "content://com.samsung.android.app.routines.modeinfoprovider"
                )
                val response = contentResolver.call(
                    modeInfoUri,
                    "get_mode_list",
                    null,
                    Bundle()
                )

                result.appendLine("Вызов выполнен")
                result.appendLine("Ответ: ${response ?: "null"}")
            } catch (e: SecurityException) {
                result.appendLine("ДОСТУП ЗАПРЕЩЁН: SecurityException")
                result.appendLine(e.message ?: "")
            } catch (e: Exception) {
                result.appendLine("ОШИБКА ВЫЗОВА: ${e.javaClass.simpleName}")
                result.appendLine(e.message ?: "")
            }

            result.appendLine()
            result.appendLine("Provider authority:")
            result.appendLine(authority)
            result.appendLine("Сам факт обнаружения не означает доступность чтения.")

        } catch (e: PackageManager.NameNotFoundException) {
            result.appendLine("ПАКЕТ НЕ НАЙДЕН")
        } catch (e: Exception) {
            result.appendLine("ОШИБКА: ${e.javaClass.simpleName}")
            result.appendLine(e.message ?: "")
        }

        return result.toString()
    }
}
