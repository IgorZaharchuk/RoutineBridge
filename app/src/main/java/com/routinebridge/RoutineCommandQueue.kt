package com.routinebridge

import java.util.LinkedHashMap

internal data class RoutineCommandSnapshot(
    val routineUuid: Long,
    val transform: BooleanStateTransform
)

internal data class RoutineEnqueueResult(
    val cancelledRequestIds: List<String>
)

internal class RoutineCommandQueue {

    private data class Pending(
        val transform: BooleanStateTransform,
        val requestIds: List<String>
    )

    private data class InFlight(
        val routineUuid: Long,
        var baselineState: Boolean?,
        var attemptTransform: BooleanStateTransform,
        var followUpTransform: BooleanStateTransform = BooleanStateTransform.IDENTITY,
        var attemptRequestIds: MutableList<String>,
        var followUpRequestIds: MutableList<String> = mutableListOf(),
        val relatedRequestIds: MutableList<String> = mutableListOf()
    ) {
        fun snapshot() = RoutineCommandSnapshot(
            routineUuid,
            attemptTransform.then(followUpTransform)
        )

        fun attemptTargetState(): Boolean? =
            baselineState?.let(attemptTransform::applyTo)

        fun desiredState(): Boolean? =
            attemptTargetState()?.let(followUpTransform::applyTo)
    }

    private val pending = LinkedHashMap<Long, Pending>()
    private var inFlight: InFlight? = null
    @Synchronized
    fun enqueue(
        routineUuid: Long,
        transform: BooleanStateTransform,
        requestId: String? = null
    ): RoutineEnqueueResult {
        val eventIds = listOfNotNull(requestId)

        if (transform == BooleanStateTransform.IDENTITY) {
            return RoutineEnqueueResult(eventIds)
        }

        val active = inFlight
        if (active?.routineUuid == routineUuid) {
            active.followUpTransform =
                active.followUpTransform.then(transform)
            active.followUpRequestIds.addAll(eventIds)

            if (active.followUpTransform == BooleanStateTransform.IDENTITY) {
                val cancelled = active.followUpRequestIds.toList()
                active.followUpRequestIds.clear()
                return RoutineEnqueueResult(cancelled)
            }

            return RoutineEnqueueResult(emptyList())
        }

        return RoutineEnqueueResult(
            enqueuePending(routineUuid, transform, eventIds)
        )
    }

    private fun enqueuePending(
        routineUuid: Long,
        transform: BooleanStateTransform,
        requestIds: List<String>,
        atFront: Boolean = false
    ): List<String> {
        val previous = pending[routineUuid]
        val combined = (previous?.transform
            ?: BooleanStateTransform.IDENTITY).then(transform)
        val combinedIds = previous?.requestIds.orEmpty() + requestIds

        if (combined == BooleanStateTransform.IDENTITY) {
            pending.remove(routineUuid)
            return combinedIds
        }

        val entry = Pending(combined, combinedIds)
        if (!atFront || pending.isEmpty()) {
            pending[routineUuid] = entry
            return emptyList()
        }

        val reordered = LinkedHashMap<Long, Pending>()
        reordered[routineUuid] = entry
        pending.forEach { (uuid, value) ->
            if (uuid != routineUuid) reordered[uuid] = value
        }
        pending.clear()
        pending.putAll(reordered)
        return emptyList()
    }

    @Synchronized
    fun pendingRequestIds(routineUuid: Long): List<String> =
        pending[routineUuid]?.requestIds.orEmpty().toList()

    @Synchronized
    fun nextPendingUuid(): Long? = pending.keys.firstOrNull()

    @Synchronized
    fun beginNext(
        routineUuid: Long,
        baselineState: Boolean? = null
    ): RoutineCommandSnapshot? {
        if (inFlight != null) return null
        if (pending.keys.firstOrNull() != routineUuid) return null

        val entry = pending.remove(routineUuid) ?: return null
        val active = InFlight(
            routineUuid = routineUuid,
            baselineState = baselineState,
            attemptTransform = entry.transform,
            attemptRequestIds = entry.requestIds.toMutableList()
        )
        inFlight = active
        return active.snapshot()
    }

    @Synchronized
    fun inFlightSnapshot(): RoutineCommandSnapshot? = inFlight?.snapshot()

    @Synchronized
    fun inFlightAttemptTransform(): BooleanStateTransform? =
        inFlight?.attemptTransform

    /**
     * IDs of events that caused the current physical attempt, followed by
     * IDs of earlier attempts in the same logical reconciliation.
     */
    @Synchronized
    fun inFlightRequestIds(): List<String> {
        val active = inFlight ?: return emptyList()
        return (
            active.attemptRequestIds +
                active.relatedRequestIds +
                active.followUpRequestIds
            ).distinct()
    }

    @Synchronized
    fun setInFlightBaseline(state: Boolean): Boolean? {
        val active = inFlight ?: return null
        active.baselineState = state
        return active.attemptTargetState()
    }

    @Synchronized
    fun inFlightAttemptTargetState(): Boolean? =
        inFlight?.attemptTargetState()

    @Synchronized
    fun inFlightDesiredState(): Boolean? = inFlight?.desiredState()

    /**
     * Completes the physical attempt successfully.
     * Any follow-up is evaluated against the confirmed Provider state.
     * Returns true when the whole logical command is now satisfied.
     */
    @Synchronized
    fun advanceAfterSuccess(
        initialState: Boolean,
        actualState: Boolean
    ): Boolean {
        val active = inFlight ?: return false
        val desired = active.followUpTransform.applyTo(
            active.attemptTransform.applyTo(initialState)
        )

        if (actualState == desired) {
            inFlight = null
            return true
        }

        // The previous physical attempt is complete. Turn the remaining
        // desired state into a new attempt based on the actual Provider state.
        active.baselineState = actualState
        active.relatedRequestIds.addAll(active.attemptRequestIds)
        active.attemptTransform = if (desired) {
            BooleanStateTransform.ENABLE
        } else {
            BooleanStateTransform.DISABLE
        }
        active.attemptRequestIds = active.followUpRequestIds.toMutableList()
        active.followUpRequestIds.clear()
        active.followUpTransform = BooleanStateTransform.IDENTITY
        return false
    }

    /**
     * Terminates the current physical attempt without retrying it.
     * Commands received during that attempt survive as a new pending entry.
     */
    @Synchronized
    fun releaseInFlight(
        preserveAttempt: Boolean = false
    ): RoutineCommandSnapshot? {
        val active = inFlight ?: return null
        inFlight = null

        val remaining = if (preserveAttempt) {
            active.attemptTransform.then(active.followUpTransform)
        } else {
            active.followUpTransform
        }
        val remainingIds = if (preserveAttempt) {
            active.attemptRequestIds + active.followUpRequestIds +
                active.relatedRequestIds
        } else {
            active.followUpRequestIds
        }

        if (remaining != BooleanStateTransform.IDENTITY) {
            enqueuePending(
                active.routineUuid,
                remaining,
                remainingIds.distinct(),
                atFront = true
            )
        }

        return active.snapshot()
    }

    @Synchronized
    fun pendingSnapshot(): List<RoutineCommandSnapshot> =
        pending.map { (uuid, entry) ->
            RoutineCommandSnapshot(uuid, entry.transform)
        }

    @Synchronized
    fun hasInFlight(): Boolean = inFlight != null
}
