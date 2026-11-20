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
            "Тест выполняется отдельно от рабочего RootCommand. " +
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
                        "Сценарий $id будет действительно запущен. " +
                            "Android может заблокировать запуск Activity из root-процесса."
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
                            "Вызов startActivity принят без исключения. " +
                                "Фактический запуск сценария нужно проверить на устройстве."
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
