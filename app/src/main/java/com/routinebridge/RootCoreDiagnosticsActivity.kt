package com.routinebridge

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.net.Uri
import android.view.Gravity
import android.widget.Button
import android.widget.ArrayAdapter
import android.widget.Spinner
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.UUID

class RootCoreDiagnosticsActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor()
    private var session: RootCoreSession? = null
    private lateinit var output: TextView
    private lateinit var routineSpinners: List<Spinner>
    private var availableRoutines: List<ManualRoutine> = emptyList()

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
            "Проверка запуска Samsung-сценариев",
            17f, white
        ).apply { setPadding(0, dp(18), 0, dp(6)) })

        routineSpinners = List(3) { index ->
            Spinner(this).also { spinner ->
                prepareSpinner(spinner)
                content.addView(text("Сценарий ${index + 1}", 13f, secondary))
                content.addView(
                    spinner,
                    LinearLayout.LayoutParams(-1, dp(48)).apply {
                        bottomMargin = dp(5)
                    }
                )
            }
        }

        addButton(content, "Обновить список сценариев") {
            loadRoutines()
        }

        addButton(content, "6. Запустить первый выбранный сценарий", blue) {
            val selected = selectedRoutines(1)
            if (selected == null) {
                log("Выбери сценарий в первом списке.")
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Запустить Samsung-сценарий?")
                    .setMessage(
                        "Сценарий «${selected.first().name}» будет включён через общий диспетчер."
                    )
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Запустить") { _, _ ->
                        runTest("Одиночный запуск") {
                            runDispatcherSequence(selected, "TEST")
                        }
                    }
                    .show()
            }
        }

        addButton(content, "7. Последовательно запустить три сценария", blue) {
            val selected = selectedRoutines(3)
            if (selected == null) {
                log("Выбери три разных сценария.")
            } else {
                AlertDialog.Builder(this)
                    .setTitle("Подтвердить последовательный запуск?")
                    .setMessage(
                        "Три выбранных сценария будут включены по очереди. " +
                            "Каждый следующий запустится только после подтверждения предыдущего. " +
                            "Связанные устройства могут изменить состояние."
                    )
                    .setNegativeButton("Отмена", null)
                    .setPositiveButton("Запустить") { _, _ ->
                        runTest("Последовательный запуск") {
                            runDispatcherSequence(selected, "T")
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
        loadRoutines()
    }

    private fun loadRoutines() {
        worker.execute {
            try {
                val loaded = ManualRoutineRepository(applicationContext).load()
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    availableRoutines = loaded
                    val labels = listOf("— Выбери сценарий —") +
                        loaded.map { it.name }
                    routineSpinners.forEach { spinner ->
                        val adapter = ArrayAdapter(
                            this,
                            android.R.layout.simple_spinner_item,
                            labels
                        )
                        adapter.setDropDownViewResource(
                            android.R.layout.simple_spinner_dropdown_item
                        )
                        spinner.adapter = adapter
                    }
                    log("Samsung Provider: загружено сценариев — ${loaded.size}")
                }
            } catch (error: Throwable) {
                log(
                    "Ошибка загрузки сценариев: " +
                        "${error.javaClass.simpleName}: ${error.message}"
                )
            }
        }
    }

    private fun selectedRoutines(count: Int): List<ManualRoutine>? {
        if (!::routineSpinners.isInitialized) return null
        val selected = routineSpinners.take(count).mapNotNull { spinner ->
            availableRoutines.getOrNull(spinner.selectedItemPosition - 1)
        }
        if (selected.size != count) return null
        if (selected.map { it.uuid }.distinct().size != count) return null
        return selected
    }

    private fun runDispatcherSequence(
        selected: List<ManualRoutine>,
        label: String
    ): String {
        val report = mutableListOf<String>()
        val repository = ManualRoutineRepository(applicationContext)

        check(selected.isNotEmpty()) { "Не выбраны сценарии." }
        check(selected.map { it.uuid }.distinct().size == selected.size) {
            "Нельзя выбирать один сценарий несколько раз."
        }

        report.add("DISPATCHER: общий SamsungRoutineDispatcher")
        report.add("MODE: последовательное выполнение, ENABLE")
        report.add("SELECTED: ${selected.joinToString { it.name }}")

        for ((index, chosen) in selected.withIndex()) {
            val current = repository.load().firstOrNull {
                it.uuid == chosen.uuid
            } ?: error("${label}${index + 1}: сценарий исчез из Provider")

            check(current.isEnabled == true) {
                "${label}${index + 1}: '${current.name}' выключен или состояние неизвестно"
            }
            check(current.isRunning == false) {
                "${label}${index + 1}: '${current.name}' уже запущен или состояние неизвестно"
            }

            report.add("${label}${index + 1}: READY '${current.name}', is_running=false")

            val latch = CountDownLatch(1)
            val requestId = UUID.randomUUID().toString()

            SamsungRoutineDispatcher.enqueue(
                context = applicationContext,
                input = RoutineInput(
                    id = UUID.randomUUID().toString(),
                    label = "Root Core diagnostics",
                    routineUuid = current.uuid,
                    routineName = current.name,
                    action = RoutineInputAction.ENABLE
                ),
                requestId = requestId,
                onQueueDrained = { latch.countDown() }
            )

            report.add("${label}${index + 1}: ENQUEUED uuid=${current.uuid}")

            check(latch.await(15, TimeUnit.SECONDS)) {
                "${label}${index + 1}: диспетчер не завершил очередь за 15 секунд. " +
                    "Следующий сценарий не запускается."
            }

            val confirmed = repository.load().firstOrNull {
                it.uuid == current.uuid
            } ?: error("${label}${index + 1}: сценарий исчез после запуска")

            check(confirmed.isRunning == true) {
                "${label}${index + 1}: запуск не подтверждён Provider; " +
                    "is_running=${confirmed.isRunning}"
            }

            report.add("${label}${index + 1}: CONFIRMED is_running=true")
        }

        report.add("RESULT: все выбранные сценарии подтверждены Provider")
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

    private fun prepareSpinner(spinner: Spinner) {
        spinner.setPopupBackgroundDrawable(rounded(Color.rgb(30, 30, 30), 8))
        spinner.backgroundTintList =
            android.content.res.ColorStateList.valueOf(Color.rgb(48, 64, 86))
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
