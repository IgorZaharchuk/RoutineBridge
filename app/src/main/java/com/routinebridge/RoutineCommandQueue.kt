package com.routinebridge

import java.util.LinkedHashMap

internal data class RoutineCommandSnapshot(
    val routineUuid: Long,
    val transform: BooleanStateTransform
)

/**
 * Thread-safe command accumulator.
 *
 * There is at most one in-flight routine. Commands for that UUID continue
 * composing into the in-flight transformation. All other UUIDs are coalesced
 * in insertion order, regardless of commands for other UUIDs between them.
 */
internal class RoutineCommandQueue {

    private data class InFlight(
        val routineUuid: Long,
        val baselineState: Boolean,
        var transform: BooleanStateTransform
    )

    private val pending =
        LinkedHashMap<Long, BooleanStateTransform>()

    private var inFlight: InFlight? = null

    @Synchronized
    fun enqueue(
        routineUuid: Long,
        transform: BooleanStateTransform
    ) {
        if (transform == BooleanStateTransform.IDENTITY) return

        val active = inFlight
        if (active?.routineUuid == routineUuid) {
            active.transform = active.transform.then(transform)
            return
        }

        val combined = (pending[routineUuid]
            ?: BooleanStateTransform.IDENTITY).then(transform)

        if (combined == BooleanStateTransform.IDENTITY) {
            pending.remove(routineUuid)
        } else {
            // Updating an existing LinkedHashMap value preserves its position.
            pending[routineUuid] = combined
        }
    }

    @Synchronized
    fun beginNext(baselineState: Boolean): RoutineCommandSnapshot? {
        if (inFlight != null || pending.isEmpty()) return null

        val first = pending.entries.first()
        pending.remove(first.key)

        inFlight = InFlight(
            routineUuid = first.key,
            baselineState = baselineState,
            transform = first.value
        )

        return inFlight!!.snapshot()
    }

    @Synchronized
    fun inFlightSnapshot(): RoutineCommandSnapshot? =
        inFlight?.snapshot()

    @Synchronized
    fun inFlightDesiredState(): Boolean? {
        val active = inFlight ?: return null
        return active.transform.applyTo(active.baselineState)
    }

    /**
     * Completes the active UUID only when the actual Provider state matches
     * the latest desired state, including commands received during execution.
     */
    @Synchronized
    fun completeIfSatisfied(actualState: Boolean): Boolean {
        val active = inFlight ?: return false
        val desired = active.transform.applyTo(active.baselineState)

        if (actualState != desired) return false

        inFlight = null
        return true
    }

    @Synchronized
    fun pendingSnapshot(): List<RoutineCommandSnapshot> =
        pending.map { (uuid, transform) ->
            RoutineCommandSnapshot(uuid, transform)
        }

    @Synchronized
    fun hasInFlight(): Boolean = inFlight != null

    private fun InFlight.snapshot() =
        RoutineCommandSnapshot(routineUuid, transform)
}
