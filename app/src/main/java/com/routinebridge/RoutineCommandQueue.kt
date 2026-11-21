package com.routinebridge

import java.util.LinkedHashMap

internal data class RoutineCommandSnapshot(
    val routineUuid: Long,
    val transform: BooleanStateTransform
)

internal data class RoutinePendingToken(
    val routineUuid: Long,
    val revision: Long
)

internal class RoutineCommandQueue {

    private data class Pending(
        val transform: BooleanStateTransform,
        val revision: Long
    )

    private data class InFlight(
        val routineUuid: Long,
        var baselineState: Boolean,
        var attemptTransform: BooleanStateTransform,
        var followUpTransform: BooleanStateTransform = BooleanStateTransform.IDENTITY
    ) {
        fun snapshot() = RoutineCommandSnapshot(
            routineUuid,
            attemptTransform.then(followUpTransform)
        )

        fun attemptTargetState() = attemptTransform.applyTo(baselineState)

        fun desiredState() = followUpTransform.applyTo(attemptTargetState())
    }

    private val pending = LinkedHashMap<Long, Pending>()
    private var inFlight: InFlight? = null
    private var nextRevision = 0L

    @Synchronized
    fun enqueue(routineUuid: Long, transform: BooleanStateTransform) {
        if (transform == BooleanStateTransform.IDENTITY) return

        val active = inFlight
        if (active?.routineUuid == routineUuid) {
            active.followUpTransform =
                active.followUpTransform.then(transform)
            return
        }

        enqueuePending(routineUuid, transform)
    }

    private fun enqueuePending(
        routineUuid: Long,
        transform: BooleanStateTransform,
        atFront: Boolean = false
    ) {
        val combined = (pending[routineUuid]?.transform
            ?: BooleanStateTransform.IDENTITY).then(transform)

        if (combined == BooleanStateTransform.IDENTITY) {
            pending.remove(routineUuid)
            return
        }

        val entry = Pending(combined, ++nextRevision)
        if (!atFront || pending.isEmpty()) {
            pending[routineUuid] = entry
            return
        }

        val reordered = LinkedHashMap<Long, Pending>()
        reordered[routineUuid] = entry
        pending.forEach { (uuid, value) ->
            if (uuid != routineUuid) reordered[uuid] = value
        }
        pending.clear()
        pending.putAll(reordered)
    }

    @Synchronized
    fun nextPendingUuid(): Long? = pending.keys.firstOrNull()

    @Synchronized
    fun pendingToken(routineUuid: Long): RoutinePendingToken? {
        val entry = pending[routineUuid] ?: return null
        return RoutinePendingToken(routineUuid, entry.revision)
    }

    @Synchronized
    fun discardPending(token: RoutinePendingToken): Boolean {
        val current = pending[token.routineUuid] ?: return false
        if (current.revision != token.revision) return false
        pending.remove(token.routineUuid)
        return true
    }

    @Synchronized
    fun beginNext(
        routineUuid: Long,
        baselineState: Boolean
    ): RoutineCommandSnapshot? {
        if (inFlight != null) return null
        if (pending.keys.firstOrNull() != routineUuid) return null

        val entry = pending.remove(routineUuid) ?: return null
        val active = InFlight(
            routineUuid = routineUuid,
            baselineState = baselineState,
            attemptTransform = entry.transform
        )
        inFlight = active
        return active.snapshot()
    }

    @Synchronized
    fun inFlightSnapshot(): RoutineCommandSnapshot? = inFlight?.snapshot()

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
    fun advanceAfterSuccess(actualState: Boolean): Boolean {
        val active = inFlight ?: return false
        val desired = active.desiredState()

        if (actualState == desired) {
            inFlight = null
            return true
        }

        // The previous physical attempt is complete. Turn the remaining
        // desired state into a new attempt based on the actual Provider state.
        active.baselineState = actualState
        active.attemptTransform = if (desired) {
            BooleanStateTransform.ENABLE
        } else {
            BooleanStateTransform.DISABLE
        }
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

        if (remaining != BooleanStateTransform.IDENTITY) {
            enqueuePending(
                active.routineUuid,
                remaining,
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
