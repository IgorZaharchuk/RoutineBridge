package com.routinebridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.routinebridge.rootcore.RootCoreSession
import java.util.concurrent.Executors

/**
 * Android shell for the process-wide serialized Samsung worker.
 *
 * Queueing and worker lifecycle are owned by RoutineWorkerCoordinator.
 */
internal object SamsungRoutineDispatcher {

    private const val TAG = "RoutineBridge"

    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "SamsungRoutineWorker")
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private val coordinator = RoutineWorkerCoordinator {
        worker.execute { drainQueue() }
    }

    @Volatile
    private var appContext: Context? = null

    fun whenWorkerStopped(callback: () -> Unit) {
        coordinator.whenWorkerStopped(callback)
    }

    fun isWorkerStopped(): Boolean =
        coordinator.isWorkerStopped()

    fun enqueue(
        context: Context,
        input: RoutineInput,
        requestId: String,
        onQueueDrained: () -> Unit
    ) {
        appContext = context.applicationContext

        val result = coordinator.enqueue(
            routineUuid = input.routineUuid,
            transform = BooleanStateTransform.from(input.action),
            requestId = requestId,
            onWorkerStopped = onQueueDrained
        )

        if (result.cancelledRequestIds.isNotEmpty()) {
            Log.i(
                TAG,
                "DISPATCH_COALESCED_CANCELLED uuid=${input.routineUuid} " +
                    "requests=${result.cancelledRequestIds.joinToString(",")}"
            )
        }

        Log.i(
            TAG,
            "DISPATCH_ENQUEUED request=$requestId input=${input.id} " +
                "uuid=${input.routineUuid} action=${input.action}"
        )
    }

    fun enqueueBatch(
        context: Context,
        requests: List<RoutineDispatchRequest>,
        onQueueDrained: () -> Unit
    ) {
        require(requests.isNotEmpty()) { "Dispatch batch must not be empty" }
        appContext = context.applicationContext

        val results = coordinator.enqueueBatch(
            commands = requests.map { request ->
                RoutineWorkerEnqueue(
                    routineUuid = request.input.routineUuid,
                    transform = BooleanStateTransform.from(request.input.action),
                    requestId = request.requestId
                )
            },
            onWorkerStopped = onQueueDrained
        )

        requests.forEachIndexed { index, request ->
            val cancelled = results[index].cancelledRequestIds
            if (cancelled.isNotEmpty()) {
                Log.i(
                    TAG,
                    "DISPATCH_COALESCED_CANCELLED uuid=${request.input.routineUuid} " +
                        "requests=${cancelled.joinToString(",")}"
                )
            }
            Log.i(
                TAG,
                "DISPATCH_BATCH_ENQUEUED request=${request.requestId} " +
                    "uuid=${request.input.routineUuid} action=${request.input.action}"
            )
        }
        Log.i(TAG, "DISPATCH_BATCH_READY size=${requests.size}")
    }

    private fun drainQueue() {
        var session: RootCoreSession? = null
        Log.i(TAG, "WORKER_CYCLE_START")

        try {
            while (true) {
                val uuid = coordinator.nextPendingUuid()

                if (uuid == null) {
                    if (coordinator.stopIfNoPending()) {
                        Log.i(TAG, "WORKER_QUEUE_EMPTY")
                        break
                    }
                    continue
                }

                // One RootCore session is shared by every operation handled
                // during this active worker cycle.
                if (processRoutine(
                    uuid = uuid,
                    getSession = { context ->
                        session ?: RootCoreAccess.open(context).also {
                            session = it
                        }
                    },
                    invalidateSession = { failedSession ->
                        if (session === failedSession) {
                            RootCoreAccess.close(failedSession)
                            session = null
                        }
                    }
                )) {
                    break
                }
            }
        } finally {
            // Close Root Core only after the active queue cycle has finished.
            session?.let(RootCoreAccess::close)
            Log.i(TAG, "WORKER_CYCLE_STOPPED")
            notifyWorkerStopped()
        }
    }

    private fun notifyWorkerStopped() {
        val callbacks = coordinator.takeCallbacksIfStopped() ?: return

        callbacks.forEach { callback ->
            try {
                callback()
            } catch (error: Exception) {
                Log.e(TAG, "DISPATCH_CALLBACK_FAILED", error)
            }
        }
    }

    private fun processRoutine(
        uuid: Long,
        getSession: (Context) -> RootCoreSession,
        invalidateSession: (RootCoreSession) -> Unit
    ): Boolean {
        var confirmedAttempt = false
        var performedLaunch = false
        var requestId = "not-started"
        var activeSession: RootCoreSession? = null

        try {
            // Move the command to in-flight before any setup that may fail.
            val start = coordinator.beginNext(uuid) ?: return false
            requestId = start.requestIds.firstOrNull() ?: "unknown"

            val context = appContext
                ?: throw IllegalStateException("Dispatcher context is unavailable")

            val repository = ManualRoutineRepository(context)
            val executor = RoutineActionExecutor(context, repository)
            val session = getSession(context)
            activeSession = session

            Log.i(
                TAG,
                "DISPATCH_START request=$requestId " +
                    "related=${start.requestIds.drop(1).joinToString(",")} " +
                    "uuid=$uuid transform=${start.command.transform} " +
                    "rootSession=${session.sessionId}"
            )

            while (true) {
                val attempt = coordinator.currentAttempt() ?: return false
                val requestIds = attempt.requestIds
                requestId = requestIds.firstOrNull() ?: "unknown"

                val result = executor.executeOnce(
                    session = session,
                    routineUuid = uuid,
                    transform = attempt.transform,
                    requestIds = requestIds,
                    onStateRead = { state ->
                        check(coordinator.setInFlightBaseline(state) != null) {
                            "In-flight command disappeared before launch"
                        }
                    }
                )

                when (result.status) {
                    RoutineExecutionStatus.SUCCESS -> {
                        performedLaunch = true
                        confirmedAttempt = true
                    }

                    RoutineExecutionStatus.SKIPPED -> {
                        confirmedAttempt = true
                    }

                    RoutineExecutionStatus.TIMEOUT -> {
                        val failedRequestIds = coordinator.inFlightRequestIds()
                        val failedRequestId = failedRequestIds.firstOrNull() ?: requestId
                        coordinator.releaseAfterFailure(confirmedAttempt)

                        Log.w(
                            TAG,
                            "DISPATCH_TIMEOUT request=$failedRequestId " +
                                "related=${failedRequestIds.drop(1).joinToString(",")} " +
                                "uuid=$uuid target=${result.targetState} " +
                                "actual=${result.actualState} deferred=$confirmedAttempt"
                        )

                        return confirmedAttempt
                    }
                }

                val actual = result.actualState
                    ?: throw IllegalStateException(
                        context.getString(R.string.routine_state_unknown)
                    )

                // Capture causal IDs before successful reconciliation can
                // clear the in-flight command.
                val completedRequestIds = coordinator.inFlightRequestIds()
                requestId = completedRequestIds.firstOrNull() ?: "unknown"

                if (
                    coordinator.advanceAfterSuccess(
                        result.initialState,
                        actual
                    )
                ) {
                    val finalStatus =
                        if (performedLaunch) "SUCCESS" else "SKIPPED"

                    Log.i(
                        TAG,
                        "DISPATCH_$finalStatus request=$requestId " +
                            "related=${completedRequestIds.drop(1).joinToString(",")} " +
                            "uuid=$uuid state=$actual"
                    )
                    return false
                }

                val nextRequestIds = coordinator.inFlightRequestIds()
                requestId = nextRequestIds.firstOrNull() ?: "unknown"

                Log.i(
                    TAG,
                    "DISPATCH_RECONCILE request=$requestId " +
                        "related=${nextRequestIds.drop(1).joinToString(",")} " +
                        "uuid=$uuid actual=$actual " +
                        "desired=${coordinator.inFlightDesiredState()}"
                )
            }
        } catch (error: Exception) {
            activeSession?.let { failedSession ->
                if (!RootCoreAccess.isUsable(failedSession)) {
                    invalidateSession(failedSession)
                }
            }

            val failedRequestIds = coordinator.inFlightRequestIds()
            val failedRequestId = failedRequestIds.firstOrNull() ?: requestId
            val released = coordinator.releaseAfterFailure(confirmedAttempt)

            Log.e(
                TAG,
                "DISPATCH_FAILED request=$failedRequestId " +
                    "related=${failedRequestIds.drop(1).joinToString(",")} " +
                    "uuid=$uuid released=${released != null} " +
                    "deferred=$confirmedAttempt error=${error.message}",
                error
            )

            showFailure(error)
            return confirmedAttempt
        }
    }

    private fun showFailure(error: Exception) {
        val context = appContext ?: return

        val message = if (error is RootCoreAccessException) {
            context.getString(R.string.root_access_unavailable)
        } else {
            error.message ?: context.getString(R.string.action_failed)
        }

        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}

internal data class RoutineDispatchRequest(
    val input: RoutineInput,
    val requestId: String
)
