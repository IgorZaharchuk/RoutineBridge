package com.routinebridge

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.rootcore.RootCore
import com.rootcore.RootCorePolicy
import com.rootcore.RootCoreSession
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class RootCoreDiagnosticsActivity : Activity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RootCoreDiagnostics").apply { isDaemon = true }
    }

    private var session: RootCoreSession? = null
    private lateinit var output: TextView

    private val providerUri = Uri.parse(
        "content://com.samsung.android.app.routines.externalprovider/" +
            "routine_list?routine_type=normal&condition_type=manual"
    )

    private val bg = Color.rgb(18, 18, 18)
    private val surface = Color.rgb(30, 30, 30)
    private val white = Color.WHITE
    private val secondary = Color.rgb(200, 205, 215)
    private val blue = Color.rgb(48, 64, 86)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(20))
            setBackgroundColor(bg)
        }

        content.addView(text(getString(R.string.diagnostics_title), 21f, white, true))
        content.addView(
            text(getString(R.string.diagnostics_intro), 14f, secondary)
        )

        val tests = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        addTestButton(tests, getString(R.string.diagnostics_open)) {
            runTest("OPEN SESSION") {
                check(session == null) { "Session is already open" }
                val start = SystemClock.elapsedRealtime()
                session = RootCoreAccess.open(applicationContext, 15_000L)
                "Binder connected; session=${session!!.sessionId.take(8)}; " +
                    "elapsed=${SystemClock.elapsedRealtime() - start} ms"
            }
        }

        addTestButton(tests, getString(R.string.diagnostics_ping)) {
            runTest("BINDER PING / UID") {
                val uid = requireSession().ping()
                check(uid == 0) { "Expected UID 0, received $uid" }
                "ping=$uid; root UID confirmed"
            }
        }

        addTestButton(tests, "BINDER DEATH / isConnected") {
            runTest("BINDER DEATH / isConnected") {
                val policy = RootCorePolicy.builder()
                    .allowClientCodeExecution()
                    .build()
                val root = RootCore.open(applicationContext, policy, 15_000L)

                try {
                    check(root.isConnected()) {
                        "New RootCore session is not connected"
                    }

                    val id = root.createObject(
                        "com.routinebridge.RootCoreObjectProbe",
                        emptyArray(),
                        Bundle()
                    )

                    val invocationError = try {
                        root.invokeObject(
                            id,
                            "killRootProcessForDiagnostics",
                            emptyArray(),
                            Bundle()
                        )
                        null
                    } catch (error: android.os.RemoteException) {
                        error
                    }

                    check(invocationError != null) {
                        "Root process termination did not interrupt Binder invocation"
                    }

                    val deadline = SystemClock.elapsedRealtime() + 5_000L
                    while (root.isConnected() &&
                        SystemClock.elapsedRealtime() < deadline) {
                        Thread.sleep(50L)
                    }

                    check(!root.isConnected()) {
                        "isConnected() remained true after root Binder death"
                    }

                    "Connected state was true before termination\n" +
                        "Root process termination interrupted Binder call\n" +
                        "DeathRecipient updated connection state\n" +
                        "isConnected() returned false"
                } finally {
                    root.close()
                }
            }
        }

        addTestButton(tests, "DISPATCHER SESSION CACHE RECOVERY") {
            runTest("DISPATCHER SESSION CACHE RECOVERY") {
                val policy = RootCorePolicy.builder()
                    .allowClientCodeExecution()
                    .build()

                val cache = RootCoreSessionCache(
                    openSession = { context ->
                        RootCore.open(context, policy, 15_000L)
                    },
                    closeSession = { it.close() }
                )

                var first: RootCoreSession? = null
                var replacement: RootCoreSession? = null

                try {
                    val original = cache.get(applicationContext)
                    first = original

                    check(original.isConnected()) {
                        "Initial session is not connected"
                    }
                    check(cache.get(applicationContext) === original) {
                        "Cache did not reuse the initial session"
                    }

                    val id = original.createObject(
                        "com.routinebridge.RootCoreObjectProbe",
                        emptyArray(),
                        Bundle()
                    )

                    val invocationError = try {
                        original.invokeObject(
                            id,
                            "killRootProcessForDiagnostics",
                            emptyArray(),
                            Bundle()
                        )
                        null
                    } catch (error: android.os.RemoteException) {
                        error
                    }

                    check(invocationError != null) {
                        "Root termination did not interrupt Binder invocation"
                    }

                    val deadline = SystemClock.elapsedRealtime() + 5_000L
                    while (original.isConnected() &&
                        SystemClock.elapsedRealtime() < deadline) {
                        Thread.sleep(50L)
                    }

                    check(!original.isConnected()) {
                        "Original Binder connection remained alive"
                    }
                    check(cache.invalidateIfUnusable(original)) {
                        "Dead cached session was not invalidated"
                    }

                    val fresh = cache.get(applicationContext)
                    replacement = fresh

                    check(fresh !== original) {
                        "Cache returned the terminated session"
                    }
                    check(fresh.isConnected()) {
                        "Replacement session is not connected"
                    }
                    check(fresh.ping() == 0) {
                        "Replacement session did not confirm root UID"
                    }
                    check(cache.get(applicationContext) === fresh) {
                        "Replacement session was not retained in cache"
                    }

                    check(!cache.invalidateIfUnusable(original)) {
                        "Stale session invalidation affected the replacement"
                    }
                    check(cache.get(applicationContext) === fresh) {
                        "Stale session invalidation removed the replacement"
                    }

                    "Initial session was reused from cache\\n" +
                        "Root process termination interrupted Binder call\\n" +
                        "Dead cached session was invalidated\\n" +
                        "Next access opened a new connected root session (UID 0)\\n" +
                        "New session was reused from cache\\n" +
                        "Stale session could not invalidate the replacement"
                } finally {
                    cache.close()
                    first?.let {
                        runCatching { it.awaitTermination(5_000L) }
                    }
                    replacement?.let {
                        runCatching { it.awaitTermination(5_000L) }
                    }
                }
            }
        }


addTestButton(tests, "DISPATCHER REAL EXECUTOR RECOVERY") {
    runTest("DISPATCHER REAL EXECUTOR RECOVERY") {
        val policy = RootCorePolicy.builder()
            .allowClientCodeExecution()
            .allowProviderAuthority(
                "com.samsung.android.app.routines.externalprovider"
            )
            // Намеренно НЕ разрешаем launchActivity.
            .build()

        val openedSessions = mutableListOf<RootCoreSession>()
        val sessions = RootCoreSessionCache(
            openSession = { context ->
                RootCore.open(context, policy, 15_000L).also {
                    openedSessions.add(it)
                }
            },
            closeSession = { it.close() }
        )

        val repository = ManualRoutineRepository(applicationContext)
        val executor = RoutineActionExecutor(
            applicationContext,
            repository
        )
        val coordinator = RoutineWorkerCoordinator { }

        var firstSession: RootCoreSession? = null
        var secondSession: RootCoreSession? = null
        var firstFailureHandled = false
        var sessionInvalidated = false
        var secondTaskCompleted = false
        var queueStopped = false

        try {
            // Сначала читаем настоящие данные Samsung Provider.
            val initialSession = sessions.get(applicationContext)
            val candidate = repository.load(initialSession)
                .firstOrNull {
                    it.isEnabled == true && it.isRunning != null
                }
                ?: error(
                    "No enabled manual Routine with known running state"
                )

            // Преобразование должно оставить состояние неизменным.
            // Если состояние изменится, политика RootCore всё равно
            // запретит запуск Samsung Activity.
            val safeTransform =
                if (candidate.isRunning == true) {
                    BooleanStateTransform.ENABLE
                } else {
                    BooleanStateTransform.DISABLE
                }

            val failedUuid = Long.MAX_VALUE
            check(candidate.uuid != failedUuid) {
                "Diagnostic UUID conflicts with selected Routine"
            }

            coordinator.enqueue(
                routineUuid = failedUuid,
                transform = BooleanStateTransform.ENABLE,
                requestId = "diagnostic-dead-session",
                onWorkerStopped = {}
            )
            coordinator.enqueue(
                routineUuid = candidate.uuid,
                transform = safeTransform,
                requestId = "diagnostic-real-provider",
                onWorkerStopped = {}
            )

            RoutineWorkerCycle.run(
                coordinator = coordinator,
                processRoutine = { uuid ->
                    val start = coordinator.beginNext(uuid)
                        ?: error("Could not begin queued task $uuid")

                    check(start.command.routineUuid == uuid) {
                        "Worker started an unexpected task"
                    }

                    val attempt = coordinator.currentAttempt()
                        ?: error("No current worker attempt")

                    if (uuid == failedUuid) {
                        val active = sessions.get(applicationContext)
                        firstSession = active

                        val objectId = active.createObject(
                            "com.routinebridge.RootCoreObjectProbe",
                            emptyArray(),
                            Bundle()
                        )

                        // Убиваем именно процесс первой сессии.
                        // Сам вызов может завершиться DeadObjectException.
                        runCatching {
                            active.invokeObject(
                                objectId,
                                "killRootProcessForDiagnostics",
                                emptyArray(),
                                Bundle()
                            )
                        }

                        val deadline =
                            SystemClock.elapsedRealtime() + 5_000L

                        while (
                            active.isConnected() &&
                            SystemClock.elapsedRealtime() < deadline
                        ) {
                            Thread.sleep(50L)
                        }

                        check(!active.isConnected()) {
                            "First RootCore Binder remained connected"
                        }

                        // Теперь ошибку должен получить настоящий
                        // RoutineActionExecutor на вызове ping().
                        val executionError = try {
                            executor.executeOnce(
                                session = active,
                                routineUuid = uuid,
                                transform = attempt.transform,
                                requestIds = attempt.requestIds,
                                onStateRead = {
                                    error(
                                        "Executor reached Provider " +
                                            "despite dead Binder"
                                    )
                                }
                            )
                            null
                        } catch (error: Exception) {
                            error
                        }

                        check(
                            executionError is android.os.DeadObjectException
                        ) {
                            "Expected DeadObjectException from " +
                                "RoutineActionExecutor.ping(), got " +
                                (executionError?.javaClass?.simpleName
                                    ?: "no exception")
                        }

                        RoutineWorkerFailureHandler.handle(
                            activeSession = active,
                            invalidateSession = { failedSession ->
                                sessionInvalidated =
                                    sessions.invalidateIfUnusable(
                                        failedSession
                                    )
                            },
                            onFailure = {
                                val released =
                                    coordinator.releaseAfterFailure(false)
                                check(released != null) {
                                    "Failed task was not released"
                                }
                                firstFailureHandled = true
                            }
                        )

                        false
                    } else {
                        check(uuid == candidate.uuid) {
                            "Unexpected second task UUID: $uuid"
                        }

                        val replacement =
                            sessions.get(applicationContext)
                        secondSession = replacement

                        check(replacement !== firstSession) {
                            "Second task reused the dead session"
                        }
                        check(replacement.isConnected()) {
                            "Replacement RootCore session is disconnected"
                        }
                        check(replacement.ping() == 0) {
                            "Replacement session did not confirm UID 0"
                        }

                        // Настоящий RoutineActionExecutor:
                        // ping -> queryProvider -> вычисление target.
                        val result = executor.executeOnce(
                            session = replacement,
                            routineUuid = candidate.uuid,
                            transform = attempt.transform,
                            requestIds = attempt.requestIds,
                            onStateRead = { state ->
                                check(
                                    coordinator.setInFlightBaseline(state) != null
                                ) {
                                    "In-flight command disappeared"
                                }
                            }
                        )

                        check(
                            result.status == RoutineExecutionStatus.SKIPPED
                        ) {
                            "Expected safe SKIPPED result, got ${result.status}"
                        }
                        check(result.actualState == candidate.isRunning) {
                            "Routine state changed during diagnostic test"
                        }
                        check(
                            coordinator.advanceAfterSuccess(
                                result.initialState,
                                result.actualState
                                    ?: error("Provider state is unknown")
                            )
                        ) {
                            "Second queued task was not completed"
                        }

                        secondTaskCompleted = true
                        false
                    }
                },
                onQueueEmpty = {
                    queueStopped = true
                }
            )

            check(firstFailureHandled) {
                "Shared failure handler was not called"
            }
            check(sessionInvalidated) {
                "Dead RootCore session was not invalidated"
            }
            check(secondTaskCompleted) {
                "Second queued task did not complete"
            }
            check(queueStopped) {
                "Worker did not reach an empty queue"
            }
            check(openedSessions.size == 2) {
                "Expected exactly two RootCore sessions, opened " +
                    openedSessions.size
            }
            check(coordinator.isWorkerStopped()) {
                "Worker coordinator did not stop"
            }
            check(coordinator.pendingSnapshot().isEmpty()) {
                "Unexpected pending commands remain"
            }

            sessions.close()

            openedSessions.forEach { opened ->
                check(opened.awaitTermination(5_000L)) {
                    "RootCore process did not terminate"
                }
            }

            "First queued task lost its RootCore Binder\n" +
                "Real RoutineActionExecutor received DeadObjectException\n" +
                "Shared failure handler invalidated the dead session\n" +
                "Second task opened a fresh RootCore session (UID 0)\n" +
                "Real Samsung Provider was read by RoutineActionExecutor\n" +
                "Second task completed with SKIPPED; no Samsung launch allowed\n" +
                "Worker stopped and both RootCore processes terminated"
        } finally {
            sessions.close()
            openedSessions.forEach { opened ->
                runCatching { opened.awaitTermination(3_000L) }
            }
        }
    }
}

        addTestButton(tests, "DISPATCHER WORKER CYCLE RECOVERY") {
            runTest("DISPATCHER WORKER CYCLE RECOVERY") {
                val policy = RootCorePolicy.builder()
                    .allowClientCodeExecution()
                    .build()

                val openedSessions = mutableListOf<RootCoreSession>()
                val sessions = RootCoreSessionCache(
                    openSession = { context ->
                        val created = RootCore.open(
                            context,
                            policy,
                            15_000L
                        )
                        openedSessions.add(created)
                        created
                    },
                    closeSession = { it.close() }
                )

                val firstUuid = 910001L
                val secondUuid = 910002L
                val coordinator = RoutineWorkerCoordinator { }

                coordinator.enqueue(
                    routineUuid = firstUuid,
                    transform = BooleanStateTransform.ENABLE,
                    requestId = "diagnostic-first",
                    onWorkerStopped = {}
                )
                coordinator.enqueue(
                    routineUuid = secondUuid,
                    transform = BooleanStateTransform.ENABLE,
                    requestId = "diagnostic-second",
                    onWorkerStopped = {}
                )

                var firstSession: RootCoreSession? = null
                var secondSession: RootCoreSession? = null
                var firstFailureHandled = false
                var secondTaskCompleted = false
                var queueStopped = false

                try {
                    RoutineWorkerCycle.run(
                        coordinator = coordinator,
                        processRoutine = { uuid ->
                            val start = coordinator.beginNext(uuid)
                                ?: error("Could not begin queued task $uuid")

                            check(start.command.routineUuid == uuid) {
                                "Worker started an unexpected task"
                            }

                            if (uuid == firstUuid) {
                                val active = sessions.get(applicationContext)
                                firstSession = active

                                try {
                                    val objectId = active.createObject(
                                        "com.routinebridge.RootCoreObjectProbe",
                                        emptyArray(),
                                        Bundle()
                                    )

                                    active.invokeObject(
                                        objectId,
                                        "killRootProcessForDiagnostics",
                                        emptyArray(),
                                        Bundle()
                                    )

                                    error(
                                        "Root process termination did not " +
                                            "interrupt the Binder call"
                                    )
                                } catch (error: Exception) {
                                    check(error is android.os.RemoteException) {
                                        "Expected RemoteException, got " +
                                            "${error.javaClass.simpleName}: " +
                                            error.message
                                    }

                                    val deadline =
                                        SystemClock.elapsedRealtime() + 5_000L
                                    while (
                                        active.isConnected() &&
                                        SystemClock.elapsedRealtime() < deadline
                                    ) {
                                        Thread.sleep(50L)
                                    }

                                    check(!active.isConnected()) {
                                        "Root Binder remained connected"
                                    }

                                    RoutineWorkerFailureHandler.handle(
                                        activeSession = active,
                                        invalidateSession = { failedSession ->
                                            sessions.invalidateIfUnusable(failedSession)
                                            Unit
                                        }
                                    ) {
                                        coordinator.releaseAfterFailure(false)
                                        firstFailureHandled = true
                                    }

                                    false
                                }
                            } else {
                                check(uuid == secondUuid) {
                                    "Unexpected task UUID: $uuid"
                                }

                                val replacement =
                                    sessions.get(applicationContext)
                                secondSession = replacement

                                check(replacement !== firstSession) {
                                    "Second task reused the dead session"
                                }
                                check(replacement.isConnected()) {
                                    "Replacement session is not connected"
                                }
                                check(replacement.ping() == 0) {
                                    "Replacement session did not confirm UID 0"
                                }

                                val target =
                                    coordinator.setInFlightBaseline(false)
                                check(target == true) {
                                    "Second task did not produce ENABLE target"
                                }
                                check(
                                    coordinator.advanceAfterSuccess(false, true)
                                ) {
                                    "Second task was not completed"
                                }

                                secondTaskCompleted = true
                                false
                            }
                        },
                        onQueueEmpty = {
                            queueStopped = true
                        }
                    )

                    check(firstFailureHandled) {
                        "First task failure was not handled"
                    }
                    check(secondTaskCompleted) {
                        "Second task did not complete"
                    }
                    check(queueStopped) {
                        "Worker cycle did not reach the empty queue"
                    }
                    check(openedSessions.size == 2) {
                        "Expected exactly two RootCore sessions, opened " +
                            openedSessions.size
                    }
                    check(coordinator.isWorkerStopped()) {
                        "Worker coordinator did not stop"
                    }
                    check(coordinator.pendingSnapshot().isEmpty()) {
                        "Unexpected tasks remain pending"
                    }

                    sessions.close()
                    openedSessions.forEach { opened ->
                        check(opened.awaitTermination(5_000L)) {
                            "Root process did not terminate"
                        }
                    }

                    "First task lost its RootCore Binder connection\n" +
                        "Shared failure handler invalidated the dead session\n" +
                        "Second queued task opened a fresh RootCore session\n" +
                        "Replacement session confirmed UID 0\n" +
                        "Second task completed and worker cycle stopped\n" +
                        "Both RootCore processes terminated"
                } finally {
                    sessions.close()
                    openedSessions.forEach { opened ->
                        runCatching { opened.awaitTermination(3_000L) }
                    }
                }
            }
        }

        addTestButton(tests, "CLIENT OBJECTS / ROOT EXECUTION") {
            runTest("CREATE / INVOKE / STATE / RELEASE") {
                val path = "/data/system/packages.xml"

                // Запускаем тот же клиентский класс в обычном UID приложения.
                val local = com.routinebridge.RootCoreObjectProbe()
                val localResult = local.inspectFile(path)
                val localUid = localResult.getInt("uid", -1)
                check(localUid != 0) {
                    "Local probe unexpectedly runs as root"
                }

                val policy = RootCorePolicy.builder()
                    .allowClientCodeExecution()
                    .build()
                val root = RootCore.open(applicationContext, policy, 15_000L)

                var objectId: String? = null
                try {
                    objectId = root.createObject(
                        "com.routinebridge.RootCoreObjectProbe",
                        emptyArray(),
                        Bundle()
                    )

                    val invocation = root.invokeObject(
                        objectId,
                        "inspectFile",
                        arrayOf("java.lang.String"),
                        Bundle().apply { putString("arg0", path) }
                    )
                    val inspected = invocation.getBundle("value_bundle")
                        ?: error("RootCore returned no Bundle result")
                    val uid = inspected.getInt("uid", -1)
                    val pid = inspected.getInt("pid", -1)
                    val bytes = inspected.getInt("bytes_read", 0)
                    val readError = inspected.getString("read_error")

                    check(uid == 0) { "Expected UID 0, received $uid" }
                    check(pid > 0) { "Invalid root PID: $pid" }

                    val state = root.invokeObject(
                        objectId,
                        "getInvocationCount",
                        emptyArray(),
                        Bundle()
                    )
                    val count = state.getInt("value_int", -1)
                    check(count == 1) {
                        "Object state was not retained; invocation count=$count"
                    }

                    val releasedId = objectId
                    root.releaseObject(releasedId)
                    objectId = null

                    val releasedRejected = try {
                        root.invokeObject(
                            releasedId,
                            "getInvocationCount",
                            emptyArray(),
                            Bundle()
                        )
                        false
                    } catch (_: android.os.RemoteException) {
                        true
                    }
                    check(releasedRejected) {
                        "Invocation with unknown object ID was accepted"
                    }

                    "Local UID=$localUid; Root UID=$uid; PID=$pid\n" +
                        "File=$path; bytes read=$bytes\n" +
                        "Read error=${readError ?: "none"}\n" +
                        "Same object retained state: count=$count\n" +
                        "Object released; unknown ID rejected"
                } finally {
                    if (objectId != null) {
                        runCatching { root.releaseObject(objectId) }
                    }
                    root.close()
                    check(root.awaitTermination(5_000L)) {
                        "Root object session did not terminate"
                    }
                }
            }
        }

        addTestButton(tests, "DENY CLIENT CODE") {
            runTest("DENY ALL / CLIENT CODE") {
                val denied = RootCore.open(
                    applicationContext,
                    RootCorePolicy.denyAll(),
                    15_000L
                )
                try {
                    val denial = try {
                        denied.createObject(
                            "com.routinebridge.RootCoreObjectProbe",
                            emptyArray(),
                            Bundle()
                        )
                        null
                    } catch (error: SecurityException) {
                        error
                    }

                    check(denial != null) {
                        "denyAll() unexpectedly allowed client object creation"
                    }

                    check(
                        denial.message?.contains("not allowed by RootCorePolicy") == true
                    ) {
                        "Unexpected denial reason: ${denial.message}"
                    }

                    "denyAll() rejected client object creation: ${denial.message}"
                } finally {
                    denied.close()
                    check(denied.awaitTermination(5_000L)) {
                        "Denied root process did not terminate"
                    }
                }
            }
        }

        addTestButton(tests, "POLICY ISOLATION") {
            runTest("INDEPENDENT SESSION POLICIES") {
                val allowedPolicy = RootCorePolicy.builder()
                    .allowClientCodeExecution()
                    .build()

                val first = RootCore.open(
                    applicationContext,
                    allowedPolicy,
                    15_000L
                )
                var second: RootCoreSession? = null
                var objectId: String? = null

                try {
                    second = RootCore.open(
                        applicationContext,
                        RootCorePolicy.denyAll(),
                        15_000L
                    )

                    objectId = first.createObject(
                        "com.routinebridge.RootCoreObjectProbe",
                        emptyArray(),
                        Bundle()
                    )

                    val denial = try {
                        second.createObject(
                            "com.routinebridge.RootCoreObjectProbe",
                            emptyArray(),
                            Bundle()
                        )
                        null
                    } catch (error: SecurityException) {
                        error
                    }

                    check(denial != null) {
                        "Second session unexpectedly allowed client code"
                    }

                    check(first.ping() == 0) {
                        "First root session stopped responding"
                    }
                    check(second.ping() == 0) {
                        "Second root session stopped responding"
                    }

                    val state = first.invokeObject(
                        objectId,
                        "getInvocationCount",
                        emptyArray(),
                        Bundle()
                    )
                    check(state.getInt("value_int", -1) == 0) {
                        "First session object became unavailable"
                    }

                    "Session A: client code allowed; object created and callable\n" +
                        "Session B: client code denied by its own policy\n" +
                        "Both root processes remained alive"
                } finally {
                    if (objectId != null) {
                        runCatching { first.releaseObject(objectId) }
                    }
                    first.close()
                    second?.close()
                    runCatching { first.awaitTermination(5_000L) }
                    second?.let { runCatching { it.awaitTermination(5_000L) } }
                }
            }
        }


        addTestButton(tests, "OBJECT LIFECYCLE / SESSION ISOLATION") {
            runTest("OBJECT LIFECYCLE / SESSION ISOLATION") {
                val policy = RootCorePolicy.builder()
                    .allowClientCodeExecution()
                    .build()

                val first = RootCore.open(applicationContext, policy, 15_000L)
                var second: RootCoreSession? = null
                var firstObjectId: String? = null
                var secondObjectId: String? = null

                try {
                    val other = RootCore.open(applicationContext, policy, 15_000L)
                    second = other

                    val objectA = first.createObject(
                        "com.routinebridge.RootCoreObjectProbe",
                        emptyArray(),
                        Bundle()
                    )
                    firstObjectId = objectA

                    val objectB = first.createObject(
                        "com.routinebridge.RootCoreObjectProbe",
                        emptyArray(),
                        Bundle()
                    )
                    secondObjectId = objectB

                    val crossSessionRejected = try {
                        other.invokeObject(
                            objectA,
                            "getInvocationCount",
                            emptyArray(),
                            Bundle()
                        )
                        false
                    } catch (_: android.os.RemoteException) {
                        true
                    }

                    check(crossSessionRejected) {
                        "An object from session A was callable through session B"
                    }

                    val invocation = first.invokeObject(
                        objectA,
                        "inspectFile",
                        arrayOf("java.lang.String"),
                        Bundle().apply {
                            putString("arg0", "/data/system/packages.xml")
                        }
                    )
                    val result = invocation.getBundle("value_bundle")
                        ?: error("Object A returned no Bundle")

                    check(result.getInt("invocation_count", -1) == 1) {
                        "Object A did not retain its own state"
                    }

                    val stateB = first.invokeObject(
                        objectB,
                        "getInvocationCount",
                        emptyArray(),
                        Bundle()
                    )
                    check(stateB.getInt("value_int", -1) == 0) {
                        "Object B unexpectedly shared Object A state"
                    }

                    first.releaseObject(objectA)
                    firstObjectId = null

                    val invokeAfterRelease = try {
                        first.invokeObject(
                            objectA,
                            "getInvocationCount",
                            emptyArray(),
                            Bundle()
                        )
                        "RETURNED normally"
                    } catch (error: Throwable) {
                        "THREW ${error.javaClass.name}: ${error.message}"
                    }

                    val secondRelease = try {
                        first.releaseObject(objectA)
                        "RETURNED normally"
                    } catch (error: Throwable) {
                        "THREW ${error.javaClass.name}: ${error.message}"
                    }

                    check(invokeAfterRelease.startsWith(
                        "THREW android.os.RemoteException"
                    )) {
                        "INVOKE AFTER RELEASE: $invokeAfterRelease; " +
                            "SECOND RELEASE: $secondRelease"
                    }

                    check(secondRelease.startsWith(
                        "THREW android.os.RemoteException"
                    )) {
                        "DOUBLE RELEASE: $secondRelease; " +
                            "INVOKE AFTER RELEASE: $invokeAfterRelease"
                    }

                    val survivingState = first.invokeObject(
                        objectB,
                        "getInvocationCount",
                        emptyArray(),
                        Bundle()
                    )
                    check(survivingState.getInt("value_int", -1) == 0) {
                        "Releasing Object A affected Object B"
                    }

                    check(other.ping() == 0) {
                        "Independent session stopped responding"
                    }

                    "Two objects kept independent state\n" +
                        "Cross-session object access rejected\n" +
                        "Release, double release and use-after-release verified\n" +
                        "Releasing one object did not affect the other"
                } finally {
                    firstObjectId?.let {
                        runCatching { first.releaseObject(it) }
                    }
                    secondObjectId?.let {
                        runCatching { first.releaseObject(it) }
                    }

                    first.close()
                    second?.close()

                    check(first.awaitTermination(5_000L)) {
                        "First root process did not terminate"
                    }
                    second?.let {
                        check(it.awaitTermination(5_000L)) {
                            "Second root process did not terminate"
                        }
                    }
                }
            }
        }

        addTestButton(tests, "AUTO-CLOSEABLE / RELEASE") {
            runTest("AUTO-CLOSEABLE / RELEASE") {
                val policy = RootCorePolicy.builder()
                    .allowClientCodeExecution()
                    .build()
                val root = RootCore.open(applicationContext, policy, 15_000L)
                val successMarker = java.io.File(
                    cacheDir,
                    "rootcore-close-success-${java.util.UUID.randomUUID()}.txt"
                )
                val failureMarker = java.io.File(
                    cacheDir,
                    "rootcore-close-failure-${java.util.UUID.randomUUID()}.txt"
                )
                var successId: String? = null
                var failureId: String? = null

                fun configure(id: String, path: String, fail: Boolean) {
                    root.invokeObject(
                        id,
                        "configureClose",
                        arrayOf("java.lang.String", "long", "boolean"),
                        Bundle().apply {
                            putString("arg0", path)
                            putLong("arg1", 0L)
                            putBoolean("arg2", fail)
                        }
                    )
                }

                try {
                    successId = root.createObject(
                        "com.routinebridge.RootCoreAutoCloseableProbe",
                        emptyArray(),
                        Bundle()
                    )
                    configure(successId!!, successMarker.absolutePath, false)
                    root.releaseObject(successId!!)
                    val releasedId = successId!!
                    successId = null

                    val successText = successMarker.readText()
                    check(successText == "started\nclosed\n") {
                        "Expected one successful close; marker=$successText"
                    }

                    val doubleReleaseRejected = try {
                        root.releaseObject(releasedId)
                        false
                    } catch (_: android.os.RemoteException) {
                        true
                    }
                    check(doubleReleaseRejected) {
                        "Second release was not rejected"
                    }

                    failureId = root.createObject(
                        "com.routinebridge.RootCoreAutoCloseableProbe",
                        emptyArray(),
                        Bundle()
                    )
                    configure(failureId!!, failureMarker.absolutePath, true)

                    val closeFailure = try {
                        root.releaseObject(failureId!!)
                        null
                    } catch (error: android.os.RemoteException) {
                        error
                    }
                    check(closeFailure != null) {
                        "Expected close() failure to be reported"
                    }

                    val failedCloseText = failureMarker.readText()
                    check(failedCloseText == "started\n") {
                        "Unexpected failure marker: $failedCloseText"
                    }

                    val useAfterFailedCloseRejected = try {
                        root.invokeObject(
                            failureId!!,
                            "configureClose",
                            arrayOf("java.lang.String", "long", "boolean"),
                            Bundle().apply {
                                putString("arg0", failureMarker.absolutePath)
                                putLong("arg1", 0L)
                                putBoolean("arg2", false)
                            }
                        )
                        false
                    } catch (_: android.os.RemoteException) {
                        true
                    }
                    check(useAfterFailedCloseRejected) {
                        "Object remained callable after close() failed"
                    }

                    "Successful release called close() exactly once\n" +
                        "Double release rejected\n" +
                        "close() exception reported to caller\n" +
                        "Object remained released after close() failure"
                } finally {
                    successId?.let { runCatching { root.releaseObject(it) } }
                    failureId?.let { runCatching { root.releaseObject(it) } }
                    root.close()
                    runCatching { root.awaitTermination(5_000L) }
                    successMarker.delete()
                    failureMarker.delete()
                }
            }
        }

        addTestButton(tests, "AUTO-CLOSEABLE / SESSION SHUTDOWN") {
            runTest("AUTO-CLOSEABLE / SESSION SHUTDOWN") {
                val policy = RootCorePolicy.builder()
                    .allowClientCodeExecution()
                    .build()
                val root = RootCore.open(applicationContext, policy, 15_000L)
                val successMarker = java.io.File(
                    cacheDir,
                    "rootcore-shutdown-success-${java.util.UUID.randomUUID()}.txt"
                )
                val failureMarker = java.io.File(
                    cacheDir,
                    "rootcore-shutdown-failure-${java.util.UUID.randomUUID()}.txt"
                )

                fun createConfigured(path: String, fail: Boolean) {
                    val id = root.createObject(
                        "com.routinebridge.RootCoreAutoCloseableProbe",
                        emptyArray(),
                        Bundle()
                    )
                    root.invokeObject(
                        id,
                        "configureClose",
                        arrayOf("java.lang.String", "long", "boolean"),
                        Bundle().apply {
                            putString("arg0", path)
                            putLong("arg1", 0L)
                            putBoolean("arg2", fail)
                        }
                    )
                }

                try {
                    createConfigured(successMarker.absolutePath, false)
                    createConfigured(failureMarker.absolutePath, true)

                    root.close()
                    check(root.awaitTermination(5_000L)) {
                        "Root process did not terminate after session close"
                    }

                    check(successMarker.isFile) {
                        "Successful AutoCloseable was not closed"
                    }
                    check(failureMarker.isFile) {
                        "Failing AutoCloseable.close() was not attempted"
                    }
                    check(successMarker.readText() == "started\nclosed\n") {
                        "Successful object close marker is incomplete"
                    }
                    check(failureMarker.readText() == "started\n") {
                        "Failing object close marker is unexpected"
                    }

                    "Session shutdown attempted close() for both objects\n" +
                        "One close() failure did not prevent the other close()\n" +
                        "Root process terminated"
                } finally {
                    root.close()
                    runCatching { root.awaitTermination(5_000L) }
                    successMarker.delete()
                    failureMarker.delete()
                }
            }
        }

        addTestButton(tests, "AUTO-CLOSEABLE / BLOCKED CLOSE") {
            runTest("AUTO-CLOSEABLE / BLOCKED CLOSE") {
                val policy = RootCorePolicy.builder()
                    .allowClientCodeExecution()
                    .build()
                val root = RootCore.open(applicationContext, policy, 15_000L)
                val marker = java.io.File(
                    cacheDir,
                    "rootcore-blocked-close-${java.util.UUID.randomUUID()}.txt"
                )

                try {
                    val id = root.createObject(
                        "com.routinebridge.RootCoreAutoCloseableProbe",
                        emptyArray(),
                        Bundle()
                    )
                    root.invokeObject(
                        id,
                        "configureClose",
                        arrayOf("java.lang.String", "long", "boolean"),
                        Bundle().apply {
                            putString("arg0", marker.absolutePath)
                            putLong("arg1", 30_000L)
                            putBoolean("arg2", false)
                        }
                    )

                    root.close()
                    check(root.awaitTermination(5_000L)) {
                        "Blocked close() prevented root process termination"
                    }

                    check(marker.isFile) {
                        "AutoCloseable.close() did not start"
                    }
                    check(marker.readText() == "started\n") {
                        "Blocked close() unexpectedly completed before process exit"
                    }

                    "AutoCloseable.close() started and remained blocked\n" +
                        "Root process terminated within the shutdown deadline"
                } finally {
                    root.close()
                    runCatching { root.awaitTermination(5_000L) }
                    marker.delete()
                }
            }
        }

        addTestButton(tests, "RELEASE DURING INVOCATION") {
            runTest("RELEASE DURING INVOCATION") {
                val policy = RootCorePolicy.builder()
                    .allowClientCodeExecution()
                    .build()
                val root = RootCore.open(applicationContext, policy, 15_000L)

                val invocationMarker = java.io.File(
                    cacheDir,
                    "rootcore-release-invocation-${java.util.UUID.randomUUID()}.txt"
                )
                val closeMarker = java.io.File(
                    cacheDir,
                    "rootcore-release-close-${java.util.UUID.randomUUID()}.txt"
                )

                val executor = Executors.newFixedThreadPool(2)
                var objectId: String? = null

                try {
                    val id = root.createObject(
                        "com.routinebridge.RootCoreAutoCloseableProbe",
                        emptyArray(),
                        Bundle()
                    )
                    objectId = id

                    root.invokeObject(
                        id,
                        "configureClose",
                        arrayOf("java.lang.String", "long", "boolean"),
                        Bundle().apply {
                            putString("arg0", closeMarker.absolutePath)
                            putLong("arg1", 0L)
                            putBoolean("arg2", false)
                        }
                    )

                    root.invokeObject(
                        id,
                        "configureInvocation",
                        arrayOf("java.lang.String", "long"),
                        Bundle().apply {
                            putString("arg0", invocationMarker.absolutePath)
                            putLong("arg1", 3_500L)
                        }
                    )

                    val invocationFuture = executor.submit<Bundle> {
                        root.invokeObject(
                            id,
                            "runInvocation",
                            emptyArray(),
                            Bundle()
                        )
                    }

                    val invocationDeadline =
                        SystemClock.elapsedRealtime() + 5_000L

                    while (
                        (!invocationMarker.isFile ||
                            !invocationMarker.readText().contains("started\n")) &&
                        SystemClock.elapsedRealtime() < invocationDeadline
                    ) {
                        if (invocationFuture.isDone) {
                            try {
                                val earlyResult = invocationFuture.get()
                                throw IllegalStateException(
                                    "runInvocation completed before writing its start marker: $earlyResult"
                                )
                            } catch (error: java.util.concurrent.ExecutionException) {
                                throw IllegalStateException(
                                    "runInvocation failed before writing its start marker: ${error.cause ?: error}",
                                    error.cause ?: error
                                )
                            }
                        }
                        SystemClock.sleep(20L)
                    }

                    check(
                        invocationMarker.isFile &&
                            invocationMarker.readText().contains("started\n")
                    ) {
                        "Long-running invocation did not start; futureDone=${invocationFuture.isDone}"
                    }

                    val releaseFuture = executor.submit {
                        root.releaseObject(id)
                    }

                    var newCallsRejected = false
                    val rejectionDeadline =
                        SystemClock.elapsedRealtime() + 5_000L

                    while (
                        !newCallsRejected &&
                        SystemClock.elapsedRealtime() < rejectionDeadline
                    ) {
                        try {
                            root.invokeObject(
                                id,
                                "probe",
                                emptyArray(),
                                Bundle()
                            )
                        } catch (_: android.os.RemoteException) {
                            newCallsRejected = true
                        }

                        if (!newCallsRejected) SystemClock.sleep(25L)
                    }

                    check(newCallsRejected) {
                        "Object continued accepting calls after release began"
                    }
                    check(!releaseFuture.isDone) {
                        "releaseObject() returned before the active invocation finished"
                    }
                    check(!closeMarker.exists()) {
                        "AutoCloseable.close() started before the active invocation finished"
                    }

                    val invocationResult = invocationFuture.get(
                        8,
                        java.util.concurrent.TimeUnit.SECONDS
                    )
                    check(
                        invocationResult.getString("value_string") == "completed"
                    ) {
                        "Active invocation did not complete normally"
                    }

                    releaseFuture.get(
                        8,
                        java.util.concurrent.TimeUnit.SECONDS
                    )

                    check(invocationMarker.readText() == "started\nfinished\n") {
                        "Invocation markers show an incomplete or repeated invocation"
                    }
                    check(closeMarker.readText() == "started\nclosed\n") {
                        "AutoCloseable.close() did not run exactly after invocation"
                    }

                    val invokeAfterRelease = try {
                        root.invokeObject(
                            id,
                            "probe",
                            emptyArray(),
                            Bundle()
                        )
                        false
                    } catch (_: android.os.RemoteException) {
                        true
                    }

                    val doubleReleaseRejected = try {
                        root.releaseObject(id)
                        false
                    } catch (_: android.os.RemoteException) {
                        true
                    }

                    check(invokeAfterRelease) {
                        "Invocation after release was accepted"
                    }
                    check(doubleReleaseRejected) {
                        "Second release was accepted"
                    }

                    objectId = null

                    "Active invocation completed normally\n" +
                        "New invocations were rejected while release was waiting\n" +
                        "AutoCloseable.close() started only after invocation completion\n" +
                        "Use-after-release and double release rejected"
                } finally {
                    objectId?.let {
                        runCatching { root.releaseObject(it) }
                    }
                    executor.shutdownNow()
                    root.close()
                    runCatching { root.awaitTermination(5_000L) }
                    invocationMarker.delete()
                    closeMarker.delete()
                }
            }
        }

        addTestButton(tests, "OBJECT CONCURRENCY") {
            runTest("OBJECT CONCURRENCY") {
                val policy = RootCorePolicy.builder()
                    .allowClientCodeExecution()
                    .build()
                val root = RootCore.open(applicationContext, policy, 15_000L)

                val objectIds = mutableListOf<String>()

                fun invoke(
                    id: String,
                    method: String,
                    types: Array<String> = emptyArray(),
                    args: Bundle = Bundle()
                ) = root.invokeObject(id, method, types, args)

                fun reset(id: String) {
                    invoke(id, "reset")
                }

                fun maximum(id: String): Int =
                    invoke(id, "getMaximum").getInt("value_int", -1)

                fun runBatch(
                    targets: List<String>,
                    expected: Int,
                    delayMs: Long
                ) {
                    check(targets.isNotEmpty())
                    check(targets.size == expected)

                    val gate = java.util.concurrent.CountDownLatch(1)
                    val executor = Executors.newFixedThreadPool(expected)

                    try {
                        val futures = targets.map { id ->
                            executor.submit {
                                check(
                                    gate.await(
                                        5,
                                        java.util.concurrent.TimeUnit.SECONDS
                                    )
                                ) { "Client start gate timed out" }

                                invoke(
                                    id,
                                    "runConcurrent",
                                    arrayOf("int", "long"),
                                    Bundle().apply {
                                        putInt("arg0", expected)
                                        putLong("arg1", delayMs)
                                    }
                                )
                            }
                        }

                        gate.countDown()

                        futures.forEach { future ->
                            future.get(
                                9,
                                java.util.concurrent.TimeUnit.SECONDS
                            )
                        }
                    } finally {
                        gate.countDown()
                        executor.shutdownNow()
                        executor.awaitTermination(
                            2,
                            java.util.concurrent.TimeUnit.SECONDS
                        )
                    }
                }

                try {
                    repeat(2) {
                        objectIds += root.createObject(
                            "com.routinebridge.RootCoreConcurrencyProbe",
                            emptyArray(),
                            Bundle()
                        )
                    }

                    // 1. Два разных объекта.
                    reset(objectIds[0])
                    runBatch(objectIds.take(2), 2, 1_200L)
                    val separateObjectsMaximum = maximum(objectIds[0])

                    check(separateObjectsMaximum == 2) {
                        "Different objects: expected maximum=2, " +
                            "actual=$separateObjectsMaximum"
                    }

                    // 2. Два одновременных вызова одного объекта.
                    reset(objectIds[0])
                    runBatch(listOf(objectIds[0], objectIds[0]), 2, 1_200L)
                    val sameObjectTwoMaximum = maximum(objectIds[0])

                    check(sameObjectTwoMaximum == 2) {
                        "One object / two calls: expected maximum=2, " +
                            "actual=$sameObjectTwoMaximum"
                    }

                    // 3. Четыре одновременных вызова одного объекта.
                    reset(objectIds[0])
                    runBatch(
                        List(4) { objectIds[0] },
                        4,
                        1_500L
                    )
                    val sameObjectFourMaximum = maximum(objectIds[0])

                    check(sameObjectFourMaximum == 4) {
                        "One object / four calls: expected maximum=4, " +
                            "actual=$sameObjectFourMaximum"
                    }

                    "Different objects: maximum=$separateObjectsMaximum / 2\n" +
                        "One object, two calls: maximum=$sameObjectTwoMaximum / 2\n" +
                        "One object, four calls: maximum=$sameObjectFourMaximum / 4\n" +
                        "All concurrency barriers completed"
                } finally {
                    objectIds.forEach { id ->
                        runCatching { root.releaseObject(id) }
                    }
                    root.close()
                    check(root.awaitTermination(5_000L)) {
                        "Root process did not terminate"
                    }
                }
            }
        }

        addTestButton(tests, "CLOSE DURING BLOCKED BINDER CALL") {
            runTest("CLOSE DURING BLOCKED BINDER CALL") {
                val policy = RootCorePolicy.builder()
                    .allowClientCodeExecution()
                    .build()
                val root = RootCore.open(applicationContext, policy, 15_000L)
                val marker = java.io.File(
                    cacheDir,
                    "rootcore-block-${java.util.UUID.randomUUID()}.started"
                )
                marker.delete()

                var objectId: String? = null
                var invocationThread: Thread? = null
                val invocationDone = java.util.concurrent.CountDownLatch(1)
                val invocationFailure =
                    java.util.concurrent.atomic.AtomicReference<Throwable?>(null)

                try {
                    objectId = root.createObject(
                        "com.routinebridge.RootCoreObjectProbe",
                        emptyArray(),
                        Bundle()
                    )

                    val id = objectId ?: error("Object creation returned no ID")
                    invocationThread = Thread({
                        try {
                            root.invokeObject(
                                id,
                                "blockForDiagnostics",
                                arrayOf("java.lang.String"),
                                Bundle().apply {
                                    putString("arg0", marker.absolutePath)
                                }
                            )
                        } catch (error: Throwable) {
                            invocationFailure.set(error)
                        } finally {
                            invocationDone.countDown()
                        }
                    }, "RootCoreBlockedInvocation").apply {
                        isDaemon = true
                        start()
                    }

                    val deadline = SystemClock.elapsedRealtime() + 5_000L
                    while (!marker.isFile &&
                        SystemClock.elapsedRealtime() < deadline
                    ) {
                        SystemClock.sleep(20L)
                    }
                    check(marker.isFile) {
                        "Remote blocking method did not signal that it started"
                    }

                    val closeStart = SystemClock.elapsedRealtime()
                    root.close()
                    val closeElapsed = SystemClock.elapsedRealtime() - closeStart

                    check(closeElapsed < 1_500L) {
                        "close() took ${closeElapsed} ms during a blocked Binder call"
                    }
                    check(root.awaitTermination(5_000L)) {
                        "Root process did not terminate while client method was blocked"
                    }
                    check(invocationDone.await(
                        3_000L,
                        java.util.concurrent.TimeUnit.MILLISECONDS
                    )) {
                        "Blocked Binder invocation did not finish after root process termination"
                    }
                    check(invocationFailure.get() != null) {
                        "Blocked invocation unexpectedly returned normally"
                    }

                    "close() returned in ${closeElapsed} ms\n" +
                        "Root process terminated while the remote method was sleeping\n" +
                        "Blocked invocation ended with " +
                        invocationFailure.get()!!::class.java.simpleName
                } finally {
                    root.close()
                    runCatching { root.awaitTermination(5_000L) }
                    invocationThread?.interrupt()
                    marker.delete()
                }
            }
        }

        addTestButton(tests, "SESSION SHUTDOWN / BOTH ORDERS") {
            runTest("SESSION SHUTDOWN / BOTH ORDERS") {
                for (closeAFirst in listOf(true, false)) {
                    val first = RootCoreAccess.open(applicationContext, 15_000L)
                    var second: RootCoreSession? = null

                    try {
                        val other = RootCoreAccess.open(
                            applicationContext,
                            15_000L
                        )
                        second = other

                        check(first.ping() == 0) {
                            "Session A did not respond before shutdown"
                        }
                        check(other.ping() == 0) {
                            "Session B did not respond before shutdown"
                        }

                        val closing = if (closeAFirst) first else other
                        val survivor = if (closeAFirst) other else first
                        val closingName = if (closeAFirst) "A" else "B"

                        closing.close()
                        closing.close()

                        val callsRejected = try {
                            closing.ping()
                            false
                        } catch (_: IllegalStateException) {
                            true
                        }
                        check(callsRejected) {
                            "Session $closingName accepted a call after close()"
                        }

                        check(closing.awaitTermination(10_000L)) {
                            "Session $closingName root process did not terminate"
                        }

                        check(survivor.isRootProcessAlive()) {
                            "Closing session $closingName terminated the other session"
                        }
                        check(survivor.ping() == 0) {
                            "The surviving session stopped responding"
                        }

                        survivor.close()
                        check(survivor.awaitTermination(10_000L)) {
                            "The surviving root process did not terminate"
                        }
                    } finally {
                        first.close()
                        second?.close()
                        runCatching { first.awaitTermination(3_000L) }
                        second?.let {
                            runCatching { it.awaitTermination(3_000L) }
                        }
                    }
                }

                "A closed while B remained alive and responsive\n" +
                    "B closed while A remained alive and responsive\n" +
                    "Repeated close() was safe; calls after close() rejected\n" +
                    "All four root processes terminated"
            }
        }

        addTestButton(tests, getString(R.string.diagnostics_provider_allow)) {
            runTest("PROVIDER ALLOW") {
                val result = requireSession().queryProvider(providerUri, null)
                val columns = result.getStringArray("columns")
                    ?: error("Provider returned no columns")
                @Suppress("DEPRECATION")
                val rows = result.getParcelableArrayList<Bundle>("rows")
                    ?: error("Provider returned no rows field")
                "Allowed query succeeded; columns=${columns.size}; rows=${rows.size}"
            }
        }

        addTestButton(tests, getString(R.string.diagnostics_provider_deny)) {
            runTest("PROVIDER DENY") {
                val denied = RootCore.open(
                    applicationContext,
                    RootCorePolicy.denyAll(),
                    15_000L
                )
                try {
                    val denial = try {
                        denied.queryProvider(providerUri, null)
                        null
                    } catch (error: SecurityException) {
                        error
                    }

                    check(
                        denial?.message?.contains(
                            "Provider authority is not allowed:"
                        ) == true
                    ) {
                        "Expected RootCore policy denial; got: " +
                            (denial?.message ?: "query unexpectedly succeeded")
                    }

                    "Provider access denied by RootCore policy: ${denial!!.message}"
                } finally {
                    denied.close()
                    check(denied.awaitTermination(5_000L)) {
                        "Denied RootCore process did not terminate"
                    }
                }
            }
        }

        addTestButton(tests, getString(R.string.diagnostics_repeat)) {
            runTest("FIVE QUERIES / ONE SESSION") {
                val current = requireSession()
                val reports = (1..5).map { index ->
                    val start = SystemClock.elapsedRealtime()
                    val result = current.queryProvider(providerUri, null)
                    val elapsed = SystemClock.elapsedRealtime() - start
                    @Suppress("DEPRECATION")
                    val rows = result.getParcelableArrayList<Bundle>("rows")
                        ?: error("Query $index returned no rows field")
                    "  #$index: ${elapsed} ms, rows=${rows.size}"
                }
                reports.joinToString("\n")
            }
        }

        addTestButton(tests, getString(R.string.diagnostics_activity_policy)) {
            runTest("ACTIVITY POLICY") {
                val component = ComponentName(
                    "com.samsung.android.app.routines",
                    "com.samsung.android.app.routines.ui.shortcut.ShortcutLaunchActivity"
                )
                val extraName = "EXTRA_KEY_ROUTINE_UUID"
                val flags = 0x10008000

                val policy = RootCorePolicy.builder()
                    .allowActivity(
                        component,
                        null,
                        flags,
                        mapOf(extraName to RootCorePolicy.ExtraType.LONG)
                    )
                    .build()

                val allowedIntent = Intent()
                    .setComponent(component)
                    .addFlags(flags)
                    .putExtra(extraName, 123L)

                val wrongTypeIntent = Intent()
                    .setComponent(component)
                    .addFlags(flags)
                    .putExtra(extraName, 123)

                val wrongComponentIntent = Intent()
                    .setComponent(
                        ComponentName(
                            "com.samsung.android.app.routines",
                            "com.samsung.android.app.routines.ui.shortcut.OtherActivity"
                        )
                    )
                    .addFlags(flags)
                    .putExtra(extraName, 123L)

                val allowed = policy.allowsActivity(allowedIntent)
                val wrongTypeRejected = !policy.allowsActivity(wrongTypeIntent)
                val wrongComponentRejected = !policy.allowsActivity(wrongComponentIntent)

                check(allowed) { "Expected Activity was rejected" }
                check(wrongTypeRejected) { "Wrong extra type was accepted" }
                check(wrongComponentRejected) { "Wrong component was accepted" }

                "Allowed component and LONG extra; wrong type and component rejected. " +
                    "No Activity was launched."
            }
        }

        addTestButton(tests, getString(R.string.diagnostics_activity_deny)) {
            runTest("ACTIVITY DENY") {
                val denied = RootCore.open(
                    applicationContext,
                    RootCorePolicy.denyAll(),
                    15_000L
                )
                try {
                    val component = ComponentName(
                        "com.samsung.android.app.routines",
                        "com.samsung.android.app.routines.ui.shortcut.ShortcutLaunchActivity"
                    )
                    val denial = try {
                        denied.launchActivity(component, null, null, 0)
                        null
                    } catch (error: SecurityException) {
                        error
                    }

                    check(denial != null) {
                        "Expected SecurityException; forbidden Activity was accepted"
                    }
                    "Binder rejected forbidden Activity: ${denial.message}"
                } finally {
                    denied.close()
                    check(denied.awaitTermination(5_000L)) {
                        "Denied Activity session did not terminate"
                    }
                }
            }
        }

        addTestButton(tests, getString(R.string.diagnostics_provider_validation)) {
            runTest("PROVIDER INPUT VALIDATION") {
                val current = requireSession()

                val invalidUriRejected = try {
                    current.queryProvider(Uri.parse("https://example.invalid/path"), null)
                    false
                } catch (_: IllegalArgumentException) {
                    true
                }

                val invalidProjectionRejected = try {
                    current.queryProvider(providerUri, arrayOf(""))
                    false
                } catch (_: IllegalArgumentException) {
                    true
                }

                check(invalidUriRejected) {
                    "Invalid Provider URI was not rejected with IllegalArgumentException"
                }
                check(invalidProjectionRejected) {
                    "Invalid projection was not rejected with IllegalArgumentException"
                }

                "Invalid URI and projection rejected with IllegalArgumentException"
            }
        }

        addTestButton(tests, getString(R.string.diagnostics_provider_error)) {
            runTest("PROVIDER ERROR PROPAGATION") {
                val badUri = providerUri.buildUpon()
                    .path("/routinebridge_nonexistent_path")
                    .build()

                check(badUri.authority == providerUri.authority) {
                    "Test URI authority changed unexpectedly"
                }

                val failure = try {
                    requireSession().queryProvider(badUri, null)
                    null
                } catch (error: RuntimeException) {
                    error
                }

                check(failure != null) {
                    "Samsung Provider accepted the deliberately unknown column"
                }
                check(!failure.message.isNullOrBlank()) {
                    "Provider error was propagated without a useful message"
                }

                "Provider error preserved: ${failure.javaClass.simpleName}: ${failure.message}"
            }
        }

        addTestButton(tests, getString(R.string.diagnostics_close_after_failure)) {
            runTest("CLOSE AFTER FAILED CALL") {
                val current = RootCoreAccess.open(applicationContext, 15_000L)
                try {
                    val rejected = try {
                        current.queryProvider(
                            Uri.parse("https://example.invalid/path"),
                            null
                        )
                        false
                    } catch (_: IllegalArgumentException) {
                        true
                    }

                    check(rejected) {
                        "Invalid Provider URI did not produce IllegalArgumentException"
                    }

                    current.close()
                    check(current.awaitTermination(5_000L)) {
                        "Root process did not terminate after failed Binder call"
                    }

                    "Invalid Binder call rejected; session closed and root process terminated"
                } finally {
                    runCatching { current.close() }
                    runCatching { current.awaitTermination(2_000L) }
                }
            }
        }

        addTestButton(tests, getString(R.string.diagnostics_independent)) {
            runTest("INDEPENDENT SESSIONS") {
                val first = RootCoreAccess.open(applicationContext, 15_000L)
                var second: RootCoreSession? = null
                try {
                    second = RootCoreAccess.open(applicationContext, 15_000L)

                    check(first.ping() == 0) { "First session ping failed" }
                    check(second.ping() == 0) { "Second session ping failed" }

                    first.close()
                    val firstStopped = first.awaitTermination(10_000L)
                    check(firstStopped) { "First root process did not terminate" }

                    check(second.isRootProcessAlive()) {
                        "Second process died after closing the first session"
                    }
                    check(second.ping() == 0) {
                        "Second session stopped responding"
                    }

                    "First session terminated; second session remained alive and responsive"
                } finally {
                    first.close()
                    second?.close()
                    runCatching { first.awaitTermination(2_000L) }
                    second?.let { runCatching { it.awaitTermination(2_000L) } }
                }
            }
        }

        addTestButton(tests, getString(R.string.diagnostics_close)) {
            runTest("CLOSE SESSION") {
                val current = requireSession()
                current.close()

                val callsRejected = try {
                    current.ping()
                    false
                } catch (_: IllegalStateException) {
                    true
                }
                check(callsRejected) {
                    "Session accepted a call after close()"
                }

                val terminated = current.awaitTermination(10_000L)
                check(terminated) {
                    "Root process is still alive: ${current.isRootProcessAlive()}"
                }
                session = null
                "Calls after close() rejected; root process terminated"
            }
        }

        content.addView(tests)

        val copyButton = Button(this).apply {
            text = getString(android.R.string.copy)
            isAllCaps = false
            setOnClickListener {
                val clipboard = getSystemService(CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText(
                        getString(R.string.diagnostics_title),
                        output.text
                    )
                )
            }
        }
        content.addView(
            copyButton,
            LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(6)
            }
        )

        addTestButton(content, getString(R.string.diagnostics_clear)) {
            output.text = getString(R.string.diagnostics_output_empty)
        }

        content.addView(
            text(getString(R.string.diagnostics_results), 16f, white, true).apply {
                setPadding(0, dp(18), 0, dp(8))
            }
        )

        output = text(getString(R.string.diagnostics_output_empty), 12f, secondary).apply {
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(surface, 10)
            setTextIsSelectable(true)
            gravity = Gravity.TOP
        }
        content.addView(output, LinearLayout.LayoutParams(-1, -2))

        val scroll = ScrollView(this)
        scroll.addView(content)
        setContentView(scroll)
    }

    private fun requireSession(): RootCoreSession =
        session ?: error("Open a Root Core session first")

    private fun runTest(label: String, action: () -> String) {
        worker.execute {
            val start = SystemClock.elapsedRealtime()
            try {
                val result = action()
                appendLog(
                    "PASS  $label  (${SystemClock.elapsedRealtime() - start} ms)\n$result"
                )
            } catch (error: Throwable) {
                appendLog(
                    "FAIL  $label  (${SystemClock.elapsedRealtime() - start} ms)\n" +
                        "${error.javaClass.simpleName}: ${error.message ?: error}"
                )
            }
        }
    }

    private fun appendLog(message: String) {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread {
            if (!::output.isInitialized || isFinishing || isDestroyed) return@runOnUiThread
            output.append("\n\n[$time] $message")
        }
    }

    private fun addTestButton(
        parent: LinearLayout,
        label: String,
        action: () -> Unit
    ) {
        val button = Button(this).apply {
            text = label
            isAllCaps = false
            setTextColor(white)
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = rounded(blue, 9)
            stateListAnimator = null
            elevation = 0f
            minimumWidth = 0
            setPadding(dp(5), dp(4), dp(5), dp(4))
            setOnClickListener { action() }
        }
        parent.addView(
            button,
            LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(6)
            }
        )
    }

    private fun text(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(5), 0, dp(5))
    }

    private fun rounded(color: Int, radius: Int) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        worker.execute {
            session?.let { current ->
                try {
                    current.close()
                    val terminated = current.awaitTermination(5_000L)
                    if (!terminated) {
                        android.util.Log.e(
                            "RootCoreDiagnostics",
                            "Root process did not terminate within 5000 ms"
                        )
                    }
                } catch (error: Throwable) {
                    android.util.Log.e(
                        "RootCoreDiagnostics",
                        "Failed to close RootCore session",
                        error
                    )
                } finally {
                    session = null
                }
            }
        }
        worker.shutdown()
        super.onDestroy()
    }
}
