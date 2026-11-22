package com.routinebridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Process-wide serialized dispatcher for all production Samsung launches.
 */
internal object SamsungRoutineDispatcher {

    private const val TAG = "RoutineBridge"

    private val queue = RoutineCommandQueue()
    private val schedulingLock = Any()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "SamsungRoutineWorker")
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val drainCallbacks = mutableListOf<() -> Unit>()

    @Volatile
    private var appContext: Context? = null

    private var workerScheduled = false

    fun whenWorkerStopped(callback: () -> Unit) {
        val runNow = synchronized(schedulingLock) {
            if (workerScheduled || queue.hasInFlight()) {
                drainCallbacks.add(callback)
                false
            } else {
                true
            }
        }
        if (runNow) callback()
    }

    fun isWorkerStopped(): Boolean = synchronized(schedulingLock) {
        !workerScheduled && !queue.hasInFlight()
    }

    fun enqueue(
        context: Context,
        input: RoutineInput,
        requestId: String,
        onQueueDrained: () -> Unit
    ) {
        synchronized(schedulingLock) {
            appContext = context.applicationContext
            drainCallbacks.add(onQueueDrained)

            val enqueueResult = queue.enqueue(
                input.routineUuid,
                BooleanStateTransform.from(input.action),
                requestId
            )

            if (enqueueResult.cancelledRequestIds.isNotEmpty()) {
                Log.i(
                    TAG,
                    "DISPATCH_COALESCED_CANCELLED uuid=${input.routineUuid} " +
                        "requests=${enqueueResult.cancelledRequestIds.joinToString(",")}"
                )
            }

            Log.i(
                TAG,
                "DISPATCH_ENQUEUED request=$requestId input=${input.id} " +
                    "uuid=${input.routineUuid} action=${input.action}"
            )

            if (!workerScheduled) {
                workerScheduled = true
                worker.execute { drainQueue() }
            }
        }
    }

    private fun drainQueue() {
        while (true) {
            val uuid = queue.nextPendingUuid()

            if (uuid == null) {
                val queueEmpty = synchronized(schedulingLock) {
                    if (queue.nextPendingUuid() == null) {
                        workerScheduled = false
                        true
                    } else {
                        false
                    }
                }

                if (queueEmpty) {
                    notifyWorkerStopped()
                    return
                }
                continue
            }

            // A true result means a confirmed operation required correction,
            // but that correction failed. Keep its target pending and wait
            // for a genuinely new event before resuming the queue.
            if (processRoutine(uuid)) {
                notifyWorkerStopped()
                return
            }
        }
    }

    private fun notifyWorkerStopped() {
        val callbacks = synchronized(schedulingLock) {
            if (workerScheduled || queue.hasInFlight()) {
                null
            } else {
                drainCallbacks.toList().also { drainCallbacks.clear() }
            }
        } ?: return

        callbacks.forEach { callback ->
            try {
                callback()
            } catch (error: Exception) {
                Log.e(TAG, "DISPATCH_CALLBACK_FAILED", error)
            }
        }
    }

    private fun processRoutine(uuid: Long): Boolean {
        var confirmedAttempt = false
        var performedLaunch = false
        var requestId = "not-started"

        try {
            val context = appContext
                ?: throw IllegalStateException("Dispatcher context is unavailable")
            val repository = ManualRoutineRepository(context)
            val executor = RoutineActionExecutor(context, repository)

            // Atomically move the pending command to in-flight and take
            // the request ID belonging to that exact pending command.
            val start = synchronized(schedulingLock) {
                val command = queue.beginNext(uuid)
                if (command == null) {
                    null
                } else {
                    command to queue.inFlightRequestIds()
                }
            } ?: return false

            val started = start.first
            var requestIds = start.second
            requestId = requestIds.firstOrNull() ?: "unknown"

            Log.i(
                TAG,
                "DISPATCH_START request=$requestId " +
                    "related=${requestIds.drop(1).joinToString(",")} " +
                    "uuid=$uuid transform=${started.transform}"
            )

            while (true) {
                // Execute only the frozen physical transform. The executor
                // supplies its Provider baseline before launching Samsung.
                val transform = queue.inFlightAttemptTransform() ?: return false
                requestIds = queue.inFlightRequestIds()
                requestId = requestIds.firstOrNull() ?: "unknown"

                val result = executor.executeOnce(
                    routineUuid = uuid,
                    transform = transform,
                    requestIds = requestIds,
                    onStateRead = { state ->
                        check(queue.setInFlightBaseline(state) != null) {
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
                        // Provider already confirmed the requested state.
                        confirmedAttempt = true
                    }
                    RoutineExecutionStatus.TIMEOUT -> {
                        releaseAfterFailure(confirmedAttempt)
                        Log.w(
                            TAG,
                            "DISPATCH_TIMEOUT request=$requestId uuid=$uuid " +
                                "target=${result.targetState} actual=${result.actualState} " +
                                "deferred=$confirmedAttempt"
                        )

                        return confirmedAttempt
                    }
                }

                // Reconcile against the latest Provider state and all events
                // received while the physical operation was running.
                val actual = result.actualState
                    ?: throw IllegalStateException(
                        context.getString(R.string.routine_state_unknown)
                    )

                // Include events received while the physical operation
                // was running before the queue potentially clears in-flight.
                requestIds = queue.inFlightRequestIds()
                requestId = requestIds.firstOrNull() ?: "unknown"

                if (queue.advanceAfterSuccess(result.initialState, actual)) {
                    val finalStatus =
                        if (performedLaunch) "SUCCESS" else "SKIPPED"

                    Log.i(
                        TAG,
                        "DISPATCH_$finalStatus request=$requestId " +
                            "related=${requestIds.drop(1).joinToString(",")} " +
                            "uuid=$uuid state=$actual"
                    )
                    return false
                }

                requestIds = queue.inFlightRequestIds()
                requestId = requestIds.firstOrNull() ?: "unknown"

                Log.i(
                    TAG,
                    "DISPATCH_RECONCILE request=$requestId " +
                        "related=${requestIds.drop(1).joinToString(",")} " +
                        "uuid=$uuid actual=$actual " +
                        "desired=${queue.inFlightDesiredState()}"
                )
            }
        } catch (error: Exception) {
            val active = releaseAfterFailure(confirmedAttempt)

            Log.e(
                TAG,
                "DISPATCH_FAILED request=$requestId uuid=$uuid " +
                    "released=${active != null} deferred=$confirmedAttempt " +
                    "error=${error.message}",
                error
            )
            showFailure(error)

            return confirmedAttempt
        }
    }

    /**
     * Stop draining without discarding pending work.
     * The next enqueue will schedule the single worker again.
     */
    private fun releaseAfterFailure(
        preserveAttempt: Boolean
    ): RoutineCommandSnapshot? = synchronized(schedulingLock) {
        val released = queue.releaseInFlight(
            preserveAttempt = preserveAttempt
        )

        if (preserveAttempt) {
            workerScheduled = false
        }

        released
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
