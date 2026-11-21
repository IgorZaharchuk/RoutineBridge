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

            queue.enqueue(
                input.routineUuid,
                BooleanStateTransform.from(input.action)
            )

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

            val requestId = UUID.randomUUID().toString().take(8)

            // A true result means a confirmed operation required correction,
            // but that correction failed. Keep its target pending and wait
            // for a genuinely new event before resuming the queue.
            if (processRoutine(uuid, requestId)) return
        }
    }

    private fun processRoutine(uuid: Long, requestId: String): Boolean {
        var confirmedAttempt = false
        var performedLaunch = false

        try {
            val context = appContext
                ?: throw IllegalStateException("Dispatcher context is unavailable")
            val repository = ManualRoutineRepository(context)
            val executor = RoutineActionExecutor(context, repository)

            // Bind a Provider-read failure to the exact pending revision.
            // Commands arriving during the read receive a newer revision.
            val pendingToken = queue.pendingToken(uuid) ?: return false

            val initial = try {
                repository.load().firstOrNull { it.uuid == uuid }
                    ?: throw IllegalStateException(
                        context.getString(
                            R.string.routine_not_found,
                            uuid.toString()
                        )
                    )
            } catch (error: Exception) {
                val removed = queue.discardPending(pendingToken)
                Log.w(
                    TAG,
                    "DISPATCH_PENDING_FAILED uuid=$uuid " +
                        "removedRevision=${pendingToken.revision} removed=$removed"
                )
                throw error
            }

            val baseline = initial.isRunning
                ?: run {
                    val removed = queue.discardPending(pendingToken)
                    Log.w(
                        TAG,
                        "DISPATCH_STATE_UNKNOWN uuid=$uuid " +
                            "removedRevision=${pendingToken.revision} removed=$removed"
                    )
                    throw IllegalStateException(
                        context.getString(R.string.routine_state_unknown)
                    )
                }

            val started = queue.beginNext(uuid, baseline) ?: return false

            Log.i(
                TAG,
                "DISPATCH_START request=$requestId uuid=$uuid " +
                    "baseline=$baseline transform=${started.transform}"
            )

            while (true) {
                // Execute only the frozen physical attempt.
                val target = queue.inFlightAttemptTargetState() ?: return false

                val result = executor.executeOnce(
                    routineUuid = uuid,
                    targetState = target,
                    requestId = requestId
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
                        queue.releaseInFlight(
                            preserveAttempt = confirmedAttempt
                        )
                        Log.w(
                            TAG,
                            "DISPATCH_TIMEOUT request=$requestId uuid=$uuid " +
                                "target=$target actual=${result.actualState} " +
                                "deferred=$confirmedAttempt"
                        )

                        if (confirmedAttempt) pauseUntilNextEvent()
                        return confirmedAttempt
                    }
                }

                // Reconcile against the latest Provider state and all events
                // received while the physical operation was running.
                val actual = repository.load().firstOrNull { it.uuid == uuid }
                    ?.isRunning
                    ?: throw IllegalStateException(
                        context.getString(R.string.routine_state_unknown)
                    )

                if (queue.advanceAfterSuccess(actual)) {
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
            val active = queue.releaseInFlight(
                preserveAttempt = confirmedAttempt
            )

            Log.e(
                TAG,
                "DISPATCH_FAILED request=$requestId uuid=$uuid " +
                    "released=${active != null} deferred=$confirmedAttempt " +
                    "error=${error.message}",
                error
            )
            showFailure(error)

            if (confirmedAttempt) pauseUntilNextEvent()
            return confirmedAttempt
        }
    }

    /**
     * Stop draining without discarding pending work.
     * The next enqueue will schedule the single worker again.
     */
    private fun pauseUntilNextEvent() {
        synchronized(schedulingLock) {
            workerScheduled = false
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
