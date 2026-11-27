package com.routinebridge

/**
 * Shared queue traversal used by the production dispatcher and diagnostics.
 *
 * The caller owns session lifetime and per-command failure handling.
 */
internal object RoutineWorkerCycle {

    fun run(
        coordinator: RoutineWorkerCoordinator,
        processRoutine: (Long) -> Boolean,
        onQueueEmpty: () -> Unit = {}
    ) {
        while (true) {
            val uuid = coordinator.nextPendingUuid()

            if (uuid == null) {
                if (coordinator.stopIfNoPending()) {
                    onQueueEmpty()
                    break
                }
                continue
            }

            if (processRoutine(uuid)) {
                break
            }
        }
    }
}
