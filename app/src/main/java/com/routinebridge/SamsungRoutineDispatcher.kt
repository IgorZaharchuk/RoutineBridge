package com.routinebridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import java.util.UUID
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
    private val pendingRequestIds = mutableMapOf<Long, String>()

    @Volatile
    private var appContext: Context? = null

    private var workerScheduled = false

    fun whenDrained(callback: () -> Unit) {
        val runNow = synchronized(schedulingLock) {
            if (workerScheduled ||
                queue.nextPendingUuid() != null ||
                queue.hasInFlight()
            ) {
                drainCallbacks.add(callback)
                false
            } else {
                true
            }
        }
        if (runNow) callback()
    }

    fun isIdle(): Boolean = synchronized(schedulingLock) {
        !workerScheduled &&
            queue.nextPendingUuid() == null &&
            !queue.hasInFlight()
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

            val wasAlreadyInFlight =
                queue.inFlightSnapshot()?.routineUuid == input.routineUuid

            queue.enqueue(
                input.routineUuid,
                BooleanStateTransform.from(input.action)
            )

            val isPending = queue.pendingSnapshot().any {
                it.routineUuid == input.routineUuid
            }

            if (!wasAlreadyInFlight && isPending) {
                pendingRequestIds.putIfAbsent(input.routineUuid, requestId)
            } else if (!wasAlreadyInFlight) {
                pendingRequestIds.remove(input.routineUuid)
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
                val callbacks: List<() -> Unit>? =
                    synchronized(schedulingLock) {
                        if (queue.nextPendingUuid() == null) {
                            workerScheduled = false
                            drainCallbacks.toList().also {
                                drainCallbacks.clear()
                            }
                        } else {
                            null
                        }
                    }

                if (callbacks != null) {
                    callbacks.forEach { callback ->
                        try {
                            callback()
                        } catch (error: Exception) {
                            Log.e(TAG, "DISPATCH_CALLBACK_FAILED", error)
                        }
                    }
                    return
                }
                continue
            }

            // A true result means a confirmed operation required correction,
            // but that correction failed. Keep its target pending and wait
            // for a genuinely new event before resuming the queue.
            if (processRoutine(uuid)) return
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
                    command to (
                        pendingRequestIds.remove(uuid)
                            ?: UUID.randomUUID().toString().take(8)
                    )
                }
            } ?: return false

            val started = start.first
            requestId = start.second

            Log.i(
                TAG,
                "DISPATCH_START request=$requestId uuid=$uuid " +
                    "transform=${started.transform}"
            )

            while (true) {
                // Execute only the frozen physical transform. The executor
                // supplies its Provider baseline before launching Samsung.
                val transform = queue.inFlightAttemptTransform() ?: return false

                val result = executor.executeOnce(
                    routineUuid = uuid,
                    transform = transform,
                    requestId = requestId,
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

                if (queue.advanceAfterSuccess(result.initialState, actual)) {
                    val finalStatus =
                        if (performedLaunch) "SUCCESS" else "SKIPPED"

                    Log.i(
                        TAG,
                        "DISPATCH_$finalStatus request=$requestId uuid=$uuid " +
                            "state=$actual"
                    )
                    return false
                }

                Log.i(
                    TAG,
                    "DISPATCH_RECONCILE request=$requestId uuid=$uuid " +
                        "actual=$actual desired=${queue.inFlightDesiredState()}"
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
