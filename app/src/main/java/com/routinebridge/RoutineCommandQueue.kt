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
        val baselineState: Boolean,
        var transform: BooleanStateTransform
    ) {
        fun snapshot() = RoutineCommandSnapshot(routineUuid, transform)
        fun desiredState() = transform.applyTo(baselineState)
    }

    private val pending = LinkedHashMap<Long, Pending>()
    private var inFlight: InFlight? = null
    private var nextRevision = 0L

    @Synchronized
    fun enqueue(routineUuid: Long, transform: BooleanStateTransform) {
        if (transform == BooleanStateTransform.IDENTITY) return

        val active = inFlight
        if (active?.routineUuid == routineUuid) {
            active.transform = active.transform.then(transform)
            return
        }

        val combined = (pending[routineUuid]?.transform
            ?: BooleanStateTransform.IDENTITY).then(transform)

        if (combined == BooleanStateTransform.IDENTITY) {
            pending.remove(routineUuid)
        } else {
            pending[routineUuid] = Pending(combined, ++nextRevision)
        }
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
        val active = InFlight(routineUuid, baselineState, entry.transform)
        inFlight = active
        return active.snapshot()
    }

    @Synchronized
    fun inFlightSnapshot(): RoutineCommandSnapshot? = inFlight?.snapshot()

    @Synchronized
    fun inFlightDesiredState(): Boolean? = inFlight?.desiredState()

    @Synchronized
    fun completeIfSatisfied(actualState: Boolean): Boolean {
        val active = inFlight ?: return false
        if (actualState != active.desiredState()) return false
        inFlight = null
        return true
    }

    @Synchronized
    fun releaseInFlight(): RoutineCommandSnapshot? {
        val active = inFlight ?: return null
        inFlight = null
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
