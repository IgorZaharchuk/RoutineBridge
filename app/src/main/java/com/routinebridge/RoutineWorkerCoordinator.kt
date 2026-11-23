package com.routinebridge

/**
 * Owns the command queue and the lifecycle of the single worker.
 *
 * The Android dispatcher supplies only the action used to schedule work.
 * All queue/lifecycle transitions are serialized by schedulingLock.
 */
internal class RoutineWorkerCoordinator(
    private val scheduleWorker: () -> Unit
) {

    private val queue = RoutineCommandQueue()
    private val schedulingLock = Any()
    private val stoppedCallbacks = mutableListOf<() -> Unit>()

    private var workerScheduled = false

    fun whenWorkerStopped(callback: () -> Unit) {
        val runNow = synchronized(schedulingLock) {
            if (workerScheduled || queue.hasInFlight()) {
                stoppedCallbacks.add(callback)
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
        routineUuid: Long,
        transform: BooleanStateTransform,
        requestId: String,
        onWorkerStopped: () -> Unit
    ): RoutineEnqueueResult = synchronized(schedulingLock) {
        stoppedCallbacks.add(onWorkerStopped)

        val result = queue.enqueue(
            routineUuid = routineUuid,
            transform = transform,
            requestId = requestId
        )

        if (!workerScheduled) {
            workerScheduled = true
            scheduleWorker()
        }

        result
    }

    fun nextPendingUuid(): Long? = synchronized(schedulingLock) {
        queue.nextPendingUuid()
    }

    /**
     * Stops the logical worker only when there is no pending or in-flight
     * command. A deferred command may remain pending after a failed correction.
     */
    fun stopIfNoPending(): Boolean = synchronized(schedulingLock) {
        if (queue.nextPendingUuid() != null || queue.hasInFlight()) {
            false
        } else {
            workerScheduled = false
            true
        }
    }

    /**
     * Returns callbacks only after the physical worker has reached a stop
     * point. Pending deferred commands do not prevent this lifecycle event.
     */
    fun takeCallbacksIfStopped(): List<() -> Unit>? =
        synchronized(schedulingLock) {
            if (workerScheduled || queue.hasInFlight()) {
                null
            } else {
                stoppedCallbacks.toList().also {
                    stoppedCallbacks.clear()
                }
            }
        }

    fun beginNext(routineUuid: Long): RoutineWorkerStart? =
        synchronized(schedulingLock) {
            val command = queue.beginNext(routineUuid) ?: return@synchronized null

            RoutineWorkerStart(
                command = command,
                requestIds = queue.inFlightRequestIds()
            )
        }

    fun currentAttempt(): RoutineWorkerAttempt? =
        synchronized(schedulingLock) {
            val transform = queue.inFlightAttemptTransform()
                ?: return@synchronized null

            RoutineWorkerAttempt(
                transform = transform,
                requestIds = queue.inFlightRequestIds()
            )
        }

    fun inFlightRequestIds(): List<String> =
        synchronized(schedulingLock) {
            queue.inFlightRequestIds()
        }

    fun setInFlightBaseline(state: Boolean): Boolean? =
        synchronized(schedulingLock) {
            queue.setInFlightBaseline(state)
        }

    fun advanceAfterSuccess(
        initialState: Boolean,
        actualState: Boolean
    ): Boolean = synchronized(schedulingLock) {
        queue.advanceAfterSuccess(initialState, actualState)
    }

    fun inFlightDesiredState(): Boolean? =
        synchronized(schedulingLock) {
            queue.inFlightDesiredState()
        }

    fun releaseAfterFailure(
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

    // Test inspection methods. Production callers do not use these.
    internal fun pendingSnapshot(): List<RoutineCommandSnapshot> =
        synchronized(schedulingLock) {
            queue.pendingSnapshot()
        }

    internal fun pendingRequestIds(routineUuid: Long): List<String> =
        synchronized(schedulingLock) {
            queue.pendingRequestIds(routineUuid)
        }
}

internal data class RoutineWorkerStart(
    val command: RoutineCommandSnapshot,
    val requestIds: List<String>
)

internal data class RoutineWorkerAttempt(
    val transform: BooleanStateTransform,
    val requestIds: List<String>
)
