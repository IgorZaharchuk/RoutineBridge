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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
            "Две независимые root-сессии одновременно запустят два разных ручных сценария.",
            14f, Color.LTGRAY
        ))

        firstId = edit("UUID первого ручного сценария")
        secondId = edit("UUID второго ручного сценария")
        root.addView(firstId)
        root.addView(secondId)

        startButton = Button(this).apply {
            text = "Подготовить и запустить одновременно"
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
                "Оба сценария будут отправлены на запуск одновременно. " +
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
        val ready = CountDownLatch(2)
        val completed = CountDownLatch(2)
        val startGate = CountDownLatch(1)
        val launchReady = CountDownLatch(2)
        val launchGate = CountDownLatch(1)
        val abortBeforeLaunch = AtomicBoolean(false)
        var submittedTasks = 0

        try {
            appendLog("TEST: UUID A=$first, UUID B=$second")
            appendLog("LAUNCH MODE: concurrent calls; shared launch gate")

            workers.submit {
                try {
                    executeTask(
                        "T1", first, ready, startGate, launchReady,
                        launchGate, abortBeforeLaunch
                    )
                } finally {
                    completed.countDown()
                }
            }
            submittedTasks++

            workers.submit {
                try {
                    executeTask(
                        "T2", second, ready, startGate, launchReady,
                        launchGate, abortBeforeLaunch
                    )
                } finally {
                    completed.countDown()
                }
            }
            submittedTasks++

            val bothReady = ready.await(40, TimeUnit.SECONDS)
            if (!bothReady || abortBeforeLaunch.get()) {
                abortBeforeLaunch.set(true)
                appendLog("ABORT: обе задачи не подготовились; запуск не выполняется.")
            } else {
                appendLog("BARRIER: обе сессии готовы; общий старт.")
            }

            startGate.countDown()

            val bothLaunchReady = launchReady.await(45, TimeUnit.SECONDS)
            if (!bothLaunchReady || abortBeforeLaunch.get()) {
                abortBeforeLaunch.set(true)
                appendLog("ABORT: оба вызова не подготовлены; запуск отменён.")
            } else {
                appendLog("LAUNCH BARRIER: оба потока готовы; разрешаю оба вызова.")
            }
            launchGate.countDown()

            if (!completed.await(90, TimeUnit.SECONDS)) {
                appendLog(
                    "TIMEOUT: задачи ещё работают. " +
                        "Ожидаю фактического завершения; повторный тест запрещён."
                )
                completed.await()
            }

            appendLog("TEST FINISHED: обе задачи завершены.")
        } catch (error: Throwable) {
            abortBeforeLaunch.set(true)
            appendLog("COORDINATOR ERROR: ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            abortBeforeLaunch.set(true)
            startGate.countDown()
            launchGate.countDown()

            // Если отправка одной из задач не удалась, компенсируем её счётчик.
            repeat(2 - submittedTasks) {
                completed.countDown()
            }

            // Не объявляем тест завершённым, пока отправленные задачи не выйдут из finally.
            var interrupted = false
            while (completed.count > 0) {
                try {
                    completed.await()
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }

            running.set(false)
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    startButton.isEnabled = true
                }
            }

            if (interrupted) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun executeTask(
        label: String,
        routineId: Long,
        ready: CountDownLatch,
        startGate: CountDownLatch,
        launchReady: CountDownLatch,
        launchGate: CountDownLatch,
        abortBeforeLaunch: AtomicBoolean
    ) {
        var session: RootCoreSession? = null
        var readyReported = false
        var launchReadyReported = false

        try {
            val openStarted = SystemClock.elapsedRealtime()
            appendLog("$label: OPEN")
            val opened = RootCore.open(applicationContext, 30_000L)
            session = opened
            appendLog("$label: OPEN OK ${SystemClock.elapsedRealtime() - openStarted} ms")

            val pingStarted = SystemClock.elapsedRealtime()
            val uid = opened.ping()
            check(uid == 0) { "ожидался UID 0, получен $uid" }
            appendLog("$label: PING OK uid=$uid ${SystemClock.elapsedRealtime() - pingStarted} ms")

            val repository = ManualRoutineRepository(applicationContext)
            val routine = repository.load(opened).firstOrNull { it.uuid == routineId }
                ?: error("UUID=$routineId отсутствует в Samsung Provider")
            check(routine.isEnabled == true) {
                "Сценарий '${routine.name}' не включён или is_enabled неизвестен"
            }
            val initialRunning = routine.isRunning
                ?: error("Provider не вернул is_running для '${routine.name}'")

            appendLog(
                "$label: READY '${routine.name}', enabled=${routine.isEnabled}, " +
                    "is_running=$initialRunning"
            )
            readyReported = true
            ready.countDown()

            check(startGate.await(45, TimeUnit.SECONDS)) {
                "истекло ожидание общего старта"
            }
            if (abortBeforeLaunch.get()) {
                appendLog("$label: ABORTED BEFORE LAUNCH")
                return
            }

            val extras = Bundle().apply {
                putLong("EXTRA_KEY_ROUTINE_UUID", routineId)
            }
            val component = ComponentName(
                "com.samsung.android.app.routines",
                "com.samsung.android.app.routines.ui.shortcut.ShortcutLaunchActivity"
            )

            appendLog("$label: LAUNCH READY")
            launchReadyReported = true
            launchReady.countDown()

            check(launchGate.await(45, TimeUnit.SECONDS)) {
                "истекло ожидание разрешения конкурентного запуска"
            }
            check(!abortBeforeLaunch.get()) {
                "конкурентный запуск отменён"
            }

            val launchStarted = SystemClock.elapsedRealtime()
            appendLog("$label: LAUNCH CALL BEGIN at +${launchStarted - screenStartedAt}ms")
            opened.launchActivity(component, null, extras, 0x10008000)
            val launchFinished = SystemClock.elapsedRealtime()
            appendLog(
                "$label: LAUNCH CALL END at +${launchFinished - screenStartedAt}ms; " +
                    "duration=${launchFinished - launchStarted}ms"
            )

            val pollStarted = SystemClock.elapsedRealtime()
            val deadline = pollStarted + 5_000L
            var currentRunning = initialRunning
            var poll = 0

            while (currentRunning == initialRunning &&
                SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(250L)
                poll++
                val current = repository.load(opened).firstOrNull { it.uuid == routineId }
                    ?: error("UUID=$routineId исчез из Provider во время опроса")
                currentRunning = current.isRunning
                    ?: error("Provider вернул неизвестное is_running при опросе")
                appendLog("$label: POLL#$poll is_running=$currentRunning")
            }

            if (currentRunning != initialRunning) {
                appendLog(
                    "$label: STATE CHANGED $initialRunning -> $currentRunning " +
                        "in ${SystemClock.elapsedRealtime() - pollStarted} ms"
                )
            } else {
                appendLog("$label: NO STATE CHANGE within 5000 ms")
            }
        } catch (error: Throwable) {
            abortBeforeLaunch.set(true)
            appendLog("$label: ERROR ${error.javaClass.simpleName}: ${error.message}")
        } finally {
            if (!readyReported) ready.countDown()
            if (!launchReadyReported) launchReady.countDown()
            if (session != null) {
                try {
                    val closeStarted = SystemClock.elapsedRealtime()
                    session.close()
                    val stopped = session.awaitTermination(5_000L)
                    appendLog(
                        "$label: CLOSE stopped=$stopped " +
                            "alive=${session.isRootProcessAlive()} " +
                            "in ${SystemClock.elapsedRealtime() - closeStarted} ms"
                    )
                } catch (error: Throwable) {
                    appendLog("$label: CLOSE ERROR ${error.javaClass.simpleName}: ${error.message}")
                }
            }
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
