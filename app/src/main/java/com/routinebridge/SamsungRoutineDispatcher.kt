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

            try {
                processRoutine(uuid, requestId)
            } catch (error: Exception) {
                // Do not discard pending commands here: the active operation
                // may already have completed and a newer command may be queued.
                val active = queue.releaseInFlight()

                Log.e(
                    TAG,
                    "DISPATCH_FAILED request=$requestId uuid=$uuid " +
                        "released=${active != null} error=${error.message}",
                    error
                )
                showFailure(error)
            }
        }
    }

    private fun processRoutine(uuid: Long, requestId: String) {
        val context = appContext
            ?: throw IllegalStateException("Dispatcher context is unavailable")
        val repository = ManualRoutineRepository(context)
        val executor = RoutineActionExecutor(context, repository)

        // Bind a Provider-read failure to the exact pending revision.
        // Commands arriving during the read receive a newer revision
        // and must not be removed by this failure.
        val pendingToken = queue.pendingToken(uuid) ?: return

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
                "DISPATCH_PENDING_FAILED uuid=$uuid removedRevision=${pendingToken.revision} removed=$removed"
            )
            throw error
        }

        val baseline = initial.isRunning
            ?: run {
                val removed = queue.discardPending(pendingToken)
                Log.w(
                    TAG,
                    "DISPATCH_STATE_UNKNOWN uuid=$uuid removedRevision=${pendingToken.revision} removed=$removed"
                )
                throw IllegalStateException(
                    context.getString(R.string.routine_state_unknown)
                )
            }

        // beginNext also verifies that this UUID is still first in FIFO order.
        val started = queue.beginNext(uuid, baseline) ?: return

        Log.i(
            TAG,
            "DISPATCH_START request=$requestId uuid=$uuid " +
                "baseline=$baseline transform=${started.transform}"
        )

        var performedLaunch = false

        while (true) {
            val target = queue.inFlightDesiredState() ?: return

            val result = executor.executeOnce(
                routineUuid = uuid,
                targetState = target,
                requestId = requestId
            )

            if (result.status == RoutineExecutionStatus.SUCCESS) {
                performedLaunch = true
            }

            if (result.status == RoutineExecutionStatus.TIMEOUT) {
                queue.releaseInFlight()
                Log.w(
                    TAG,
                    "DISPATCH_TIMEOUT request=$requestId uuid=$uuid " +
                        "target=$target actual=${result.actualState}"
                )
                return
            }

            // Commands received during the launch may have changed the target.
            val actual = repository.load().firstOrNull { it.uuid == uuid }
                ?.isRunning
                ?: throw IllegalStateException(
                    context.getString(R.string.routine_state_unknown)
                )

            if (queue.completeIfSatisfied(actual)) {
                val finalStatus = if (performedLaunch) "SUCCESS" else "SKIPPED"

                Log.i(
                    TAG,
                    "DISPATCH_$finalStatus request=$requestId uuid=$uuid " +
                        "state=$actual"
                )
                return
            }

            Log.i(
                TAG,
                "DISPATCH_RECONCILE request=$requestId uuid=$uuid " +
                    "actual=$actual desired=${queue.inFlightDesiredState()}"
            )
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
