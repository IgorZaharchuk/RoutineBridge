package com.routinebridge

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.graphics.Color
import com.routinebridge.rootcore.RootCore
import com.routinebridge.rootcore.RootCoreSession
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class RootCoreParallelDiagnosticsActivity : Activity() {
    private val workers = Executors.newFixedThreadPool(3)
    private val running = AtomicBoolean(false)
    private val screenStartedAt = SystemClock.elapsedRealtime()
    private lateinit var firstId: EditText
    private lateinit var secondId: EditText
    private lateinit var output: TextView
    private lateinit var startButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(20))
            setBackgroundColor(Color.rgb(18, 18, 18))
        }

        root.addView(text("Параллельный тест Root Core", 21f, Color.WHITE))
        root.addView(text(
            "Две root-сессии подготовятся заранее. Второй сценарий запустится только после подтверждения первого.",
            14f, Color.LTGRAY
        ))

        firstId = edit("UUID первого ручного сценария")
        secondId = edit("UUID второго ручного сценария")
        root.addView(firstId)
        root.addView(secondId)

        startButton = Button(this).apply {
            text = "Подготовить и запустить последовательно"
            isAllCaps = false
            setOnClickListener { confirmAndStart() }
        }
        root.addView(startButton)

        output = text("Журнал теста появится здесь.", 13f, Color.LTGRAY).apply {
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(Color.rgb(30, 30, 30))
            setTextIsSelectable(true)
        }
        root.addView(output)

        root.addView(Button(this).apply {
            text = "Копировать лог"
            isAllCaps = false
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(
                    ClipData.newPlainText("RoutineBridge diagnostic log", output.text)
                )
                android.widget.Toast.makeText(
                    this@RootCoreParallelDiagnosticsActivity,
                    "Лог скопирован",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun confirmAndStart() {
        val a = firstId.text.toString().trim().toLongOrNull()
        val b = secondId.text.toString().trim().toLongOrNull()
        if (a == null || b == null || a == b) {
            appendLog("Ошибка: укажи два разных числовых UUID.")
            return
        }
        if (!running.compareAndSet(false, true)) return

        AlertDialog.Builder(this)
            .setTitle("Подтвердить переключение сценариев?")
            .setMessage(
                "Сначала будет запущен первый сценарий. Второй запустится только после подтверждения включения первого. " +
                    "ShortcutLaunchActivity переключает их состояние: " +
                    "сценарии могут включить или выключить связанные устройства. " +
                    "Автоматического возврата исходного состояния не будет."
            )
            .setNegativeButton("Отмена") { _, _ -> running.set(false) }
            .setPositiveButton("Запустить тест") { _, _ ->
                startButton.isEnabled = false
                output.text = ""
                workers.execute { runParallelTest(a, b) }
            }
            .setOnCancelListener { running.set(false) }
            .show()
    }

    private fun runParallelTest(first: Long, second: Long) {
        var firstSession: RootCoreSession? = null
        var secondSession: RootCoreSession? = null

        try {
            appendLog("TEST: T1 UUID=$first; T2 UUID=$second")
            appendLog("LAUNCH MODE: sequential; wait for confirmed state transition")

            appendLog("T1: OPEN")
            val openedFirst = RootCore.open(applicationContext, 30_000L)
            firstSession = openedFirst
            val firstUid = openedFirst.ping()
            appendLog("T1: OPEN OK; PING uid=$firstUid")
            check(firstUid == 0) { "T1: ожидался UID 0" }

            appendLog("T2: OPEN")
            val openedSecond = RootCore.open(applicationContext, 30_000L)
            secondSession = openedSecond
            val secondUid = openedSecond.ping()
            appendLog("T2: OPEN OK; PING uid=$secondUid")
            check(secondUid == 0) { "T2: ожидался UID 0" }

            val repository = ManualRoutineRepository(applicationContext)

            val firstRoutine = repository.load(openedFirst)
                .firstOrNull { it.uuid == first }
                ?: error("T1: UUID=$first отсутствует в Samsung Provider")
            check(firstRoutine.isEnabled == true) {
                "T1: сценарий '${firstRoutine.name}' выключен или состояние неизвестно"
            }
            check(firstRoutine.isRunning == false) {
                "T1: '${firstRoutine.name}' должен быть выключен перед тестом"
            }

            val secondRoutine = repository.load(openedSecond)
                .firstOrNull { it.uuid == second }
                ?: error("T2: UUID=$second отсутствует в Samsung Provider")
            check(secondRoutine.isEnabled == true) {
                "T2: сценарий '${secondRoutine.name}' выключен или состояние неизвестно"
            }
            check(secondRoutine.isRunning == false) {
                "T2: '${secondRoutine.name}' должен быть выключен перед тестом"
            }

            appendLog("T1: READY '${firstRoutine.name}', is_running=false")
            appendLog("T2: READY '${secondRoutine.name}', is_running=false")
            appendLog("SEQUENCE: сначала T1; T2 ждёт подтверждения T1")

            val firstSucceeded = launchAndWait(
                "T1", first, openedFirst, repository
            )
            if (!firstSucceeded) {
                appendLog("T2: SKIPPED — T1 не подтвердил переход состояния")
                return
            }

            appendLog("SEQUENCE: T1 подтверждён; разрешаю запуск T2")

            val secondSucceeded = launchAndWait(
                "T2", second, openedSecond, repository
            )

            appendLog(
                if (secondSucceeded) {
                    "RESULT: оба сценария успешно включены последовательно"
                } else {
                    "RESULT: T1 включён; T2 не подтвердил переход состояния"
                }
            )
        } catch (error: Throwable) {
            appendLog("TEST ERROR: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            closeSession("T2", secondSession)
            closeSession("T1", firstSession)

            running.set(false)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    startButton.isEnabled = true
                }
            }
            appendLog("TEST FINISHED")
        }
    }

    private fun launchAndWait(
        label: String,
        routineId: Long,
        session: RootCoreSession,
        repository: ManualRoutineRepository
    ): Boolean {
        val extras = Bundle().apply {
            putLong("EXTRA_KEY_ROUTINE_UUID", routineId)
        }
        val component = ComponentName(
            "com.samsung.android.app.routines",
            "com.samsung.android.app.routines.ui.shortcut.ShortcutLaunchActivity"
        )

        val launchStarted = SystemClock.elapsedRealtime()
        appendLog("$label: LAUNCH CALL BEGIN")
        session.launchActivity(component, null, extras, 0x10008000)
        appendLog(
            "$label: LAUNCH CALL END; duration=" +
                "${SystemClock.elapsedRealtime() - launchStarted}ms"
        )

        val pollStarted = SystemClock.elapsedRealtime()
        val deadline = pollStarted + 5_000L
        var poll = 0

        while (SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(250L)
            poll++

            val routine = repository.load(session)
                .firstOrNull { it.uuid == routineId }
                ?: error("$label: UUID=$routineId исчез из Provider")

            val current = routine.isRunning
                ?: error("$label: Provider вернул неизвестный is_running")

            appendLog("$label: POLL#$poll is_running=$current")

            if (current) {
                appendLog(
                    "$label: STATE CHANGED false -> true in " +
                        "${SystemClock.elapsedRealtime() - pollStarted}ms"
                )
                return true
            }
        }

        appendLog("$label: NO STATE CHANGE within 5000ms")
        return false
    }

    private fun closeSession(label: String, session: RootCoreSession?) {
        if (session == null) return

        try {
            val closeStarted = SystemClock.elapsedRealtime()
            session.close()
            val stopped = session.awaitTermination(5_000L)
            appendLog(
                "$label: CLOSE stopped=$stopped " +
                    "alive=${session.isRootProcessAlive()} " +
                    "in ${SystemClock.elapsedRealtime() - closeStarted}ms"
            )
        } catch (error: Throwable) {
            appendLog(
                "$label: CLOSE ERROR ${error.javaClass.simpleName}: ${error.message}"
            )
        }
    }

    private fun appendLog(message: String) {
        val elapsed = SystemClock.elapsedRealtime() - screenStartedAt
        runOnUiThread {
            output.append("\n[+${elapsed}ms] $message")
        }
    }

    private fun edit(hintText: String) = EditText(this).apply {
        hint = hintText
        setSingleLine(true)
        inputType = android.text.InputType.TYPE_CLASS_NUMBER
        setTextColor(Color.WHITE)
        setHintTextColor(Color.LTGRAY)
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
        // Не прерываем работающие RootCore/Provider-вызовы принудительно.
        workers.shutdown()
        super.onDestroy()
    }
}
