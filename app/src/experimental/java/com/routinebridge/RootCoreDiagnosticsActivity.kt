package com.routinebridge

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import android.net.Uri
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.routinebridge.rootcore.RootCore
import com.routinebridge.rootcore.RootCoreSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class RootCoreDiagnosticsActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private var session: RootCoreSession? = null
    private lateinit var output: TextView
    private lateinit var routineId: EditText

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

        content.addView(text("Root Core — диагностика", 21f, white))
        content.addView(text(
            "Рабочие операции и диагностика используют Root Core. " +
                "Операции выполняются в одной root-сессии.",
            14f, secondary
        ))

        addButton(content, "1. Открыть root-сессию") {
            runTest("Открытие сессии") {
                check(session == null) { "Сессия уже открыта. Сначала закрой её." }
                val start = SystemClock.elapsedRealtime()
                session = RootCore.open(this, 30_000L)
                "Binder готов за ${SystemClock.elapsedRealtime() - start} мс"
            }
        }

        addButton(content, "2. Ping и проверка UID") {
            runTest("Ping") {
                val uid = requireSession().ping()
                check(uid == 0) { "Ожидался UID 0, получен $uid" }
                "Ответ Binder получен. UID=$uid"
            }
        }

        addButton(content, "3. Первый запрос Samsung Provider") {
            runTest("Первый запрос Provider") {
                queryReport(requireSession())
            }
        }

        addButton(content, "4. Пять повторных запросов") {
            runTest("Повторные запросы") {
                val current = requireSession()
                val results = (1..5).map { index ->
                    val start = SystemClock.elapsedRealtime()
                    val result = current.queryProvider(providerUri, null)
                    val elapsed = SystemClock.elapsedRealtime() - start
                    val rows = result.getParcelableArrayList<Bundle>("rows")
                    "Запрос $index: ${elapsed} мс, строк=${rows?.size ?: 0}"
                }
                results.joinToString("\n")
            }
        }

        addButton(content, "5. Закрыть сессию и проверить процесс") {
            runTest("Закрытие сессии") {
                val current = requireSession()
                current.close()
                val stopped = current.awaitTermination(5_000L)
                if (stopped) {
                    session = null
                    "Root-процесс завершился. su/app_process больше не работает."
                } else {
                    "ОШИБКА: root-процесс не завершился за 5 секунд. " +
                        "isAlive=${current.isRootProcessAlive()}. " +
                        "Сессия сохранена для повторной проверки."
                }
            }
        }

        content.addView(text(
            "Отдельный тест запуска Activity",
            17f, white
        ).apply { setPadding(0, dp(18), 0, dp(6)) })

        routineId = EditText(this).apply {
            hint = "Числовой UUID ручного сценария"
            setSingleLine(true)
            setTextColor(white)
            setHintTextColor(secondary)
        }
        content.addView(routineId)

        addButton(content, "6. Запустить сценарий через Root Core") {
            val id = routineId.text.toString().trim().toLongOrNull()
            if (id == null) {
                log("Укажи числовой UUID сценария.")
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Запустить Samsung-сценарий?")
                    .setMessage(
                        "Сценарий $id будет запущен через системную команду am."
                    )
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Запустить") { _, _ ->
                        runTest("Запуск Activity") {
                            val extras = Bundle().apply {
                                putLong("EXTRA_KEY_ROUTINE_UUID", id)
                            }
                            requireSession().launchActivity(
                                ComponentName(
                                    "com.samsung.android.app.routines",
                                    "com.samsung.android.app.routines.ui.shortcut.ShortcutLaunchActivity"
                                ),
                                null,
                                extras,
                                0x10008000
                            )
                            "Команда am start завершилась с кодом 0. " +
                                "Проверь фактическое выполнение сценария на устройстве."
                        }
                    }
                    .show()
            }
        }

        addButton(content, "7. Выполнить полный цикл") {
            val id = routineId.text.toString().trim().toLongOrNull()
            if (id == null) {
                log("Укажи числовой UUID сценария.")
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Выполнить полный цикл?")
                    .setMessage(
                        "Сценарий $id будет запущен один раз. " +
                            "После запуска проверим is_running. " +
                            "Исходное состояние автоматически восстанавливаться не будет."
                    )
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Выполнить") { _, _ ->
                        runTest("Полный цикл") {
                            runFullCycle(id)
                        }
                    }
                    .show()
            }
        }

        output = text("Результаты тестов появятся здесь.", 13f, secondary).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            setBackgroundColor(Color.rgb(30, 30, 30))
            setTextIsSelectable(true)
        }

        addButton(content, "Скопировать диагностику") {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("Root Core diagnostics", output.text)
            )
            Toast.makeText(this, "Диагностика скопирована", Toast.LENGTH_SHORT).show()
        }

        content.addView(output)

        val scroll = ScrollView(this)
        scroll.addView(content)
        setContentView(scroll)
    }

    private fun runFullCycle(id: Long): String {
        val report = mutableListOf<String>()
        var current: RootCoreSession? = null

        try {
            check(session == null) {
                "Сначала закрой открытую root-сессию."
            }
            report.add("1. Открытие root-сессии")
            val openStart = SystemClock.elapsedRealtime()
            current = RootCore.open(this, 30_000L)
            session = current
            report.add(
                "OK: Binder готов за " +
                    "${SystemClock.elapsedRealtime() - openStart} мс"
            )

            report.add("2. Проверка Binder и UID")
            val pingStart = SystemClock.elapsedRealtime()
            val uid = current.ping()
            check(uid == 0) { "Ожидался UID 0, получен $uid" }
            report.add(
                "OK: UID=$uid, Ping " +
                    "${SystemClock.elapsedRealtime() - pingStart} мс"
            )

            report.add("3. Поиск сценария в Samsung Provider")
            val before = readRoutineRunning(current, id)
            report.add("OK: UUID=$id, is_running до запуска=$before")

            report.add("4. Запуск сценария")
            val extras = Bundle().apply {
                putLong("EXTRA_KEY_ROUTINE_UUID", id)
            }
            val launchStart = SystemClock.elapsedRealtime()
            current.launchActivity(
                ComponentName(
                    "com.samsung.android.app.routines",
                    "com.samsung.android.app.routines.ui.shortcut.ShortcutLaunchActivity"
                ),
                null,
                extras,
                0x10008000
            )
            report.add(
                "OK: am start завершился без ошибки за " +
                    "${SystemClock.elapsedRealtime() - launchStart} мс"
            )

            report.add("5. Проверка изменения is_running")
            val checkStart = SystemClock.elapsedRealtime()
            val deadline = checkStart + 5_000L
            var after = before

            while (after == before &&
                SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(250L)
                after = readRoutineRunning(current, id)
            }

            if (after != before) {
                report.add(
                    "OK: is_running изменился: $before -> $after"
                )
            } else {
                report.add(
                    "НЕ ПОДТВЕРЖДЕНО: is_running не изменился за 5 секунд. " +
                        "Текущее значение=$after"
                )
            }
        } catch (error: Throwable) {
            report.add(
                "ОШИБКА ЦИКЛА: ${error.javaClass.simpleName}: " +
                    "${error.message ?: error}"
            )
        } finally {
            if (current != null) {
                report.add("6. Закрытие root-сессии")
                try {
                    current.close()
                    val stopped = current.awaitTermination(5_000L)
                    if (stopped) {
                        session = null
                        report.add("OK: root-процесс завершился")
                    } else {
                        report.add(
                            "ОШИБКА: root-процесс не завершился за 5 секунд; " +
                                "isAlive=${current.isRootProcessAlive()}"
                        )
                    }
                } catch (error: Throwable) {
                    report.add(
                        "ОШИБКА закрытия: ${error.javaClass.simpleName}: " +
                            "${error.message ?: error}"
                    )
                }
            }
        }

        return report.joinToString("\n")
    }

    private fun readRoutineRunning(
        current: RootCoreSession,
        id: Long
    ): Boolean {
        val result = current.queryProvider(providerUri, null)
        val columns = result.getStringArray("columns")
            ?: error("Provider не вернул список столбцов")

        @Suppress("DEPRECATION")
        val rows = result.getParcelableArrayList<Bundle>("rows")
            ?: error("Provider не вернул строки")

        val uuidIndex = columns.indexOf("uuid")
        val runningIndex = columns.indexOf("is_running")

        check(uuidIndex >= 0) { "В Provider отсутствует столбец uuid" }
        check(runningIndex >= 0) { "В Provider отсутствует столбец is_running" }

        val row = rows.firstOrNull {
            it.getString("c$uuidIndex")?.toLongOrNull() == id
        } ?: error("Сценарий UUID=$id не найден в Provider")

        val value = row.getString("c$runningIndex")
            ?: error("Provider вернул пустое значение is_running")

        return when (value.trim()) {
            "1", "true", "TRUE" -> true
            "0", "false", "FALSE" -> false
            else -> error("Неизвестное значение is_running: $value")
        }
    }

    private fun queryReport(current: RootCoreSession): String {
        val start = SystemClock.elapsedRealtime()
        val result = current.queryProvider(providerUri, null)
        val elapsed = SystemClock.elapsedRealtime() - start

        val columns = result.getStringArray("columns") ?: emptyArray()
        @Suppress("DEPRECATION")
        val rows = result.getParcelableArrayList<Bundle>("rows") ?: arrayListOf()

        val lines = mutableListOf(
            "Время: ${elapsed} мс",
            "Столбцы: ${columns.joinToString()}",
            "Строк получено: ${rows.size}"
        )

        rows.take(10).forEachIndexed { index, row ->
            val values = columns.mapIndexed { columnIndex, column ->
                "$column=${row.getString("c$columnIndex")}"
            }
            lines.add("${index + 1}. ${values.joinToString(" | ")}")
        }

        if (rows.size > 10) {
            lines.add("Показаны первые 10 из ${rows.size} строк.")
        }

        return lines.joinToString("\n")
    }

    private fun requireSession(): RootCoreSession =
        session ?: throw IllegalStateException("Сначала открой root-сессию.")

    private fun runTest(label: String, action: () -> String) {
        worker.execute {
            val start = SystemClock.elapsedRealtime()
            try {
                val result = action()
                log(
                    "$label — ${SystemClock.elapsedRealtime() - start} мс\n$result"
                )
            } catch (error: Throwable) {
                log(
                    "$label — ОШИБКА\n${error.javaClass.simpleName}: " +
                        "${error.message ?: error}"
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

    private fun addButton(
        parent: LinearLayout,
        label: String,
        action: () -> Unit
    ) {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(white)
            setBackgroundColor(blue)
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
