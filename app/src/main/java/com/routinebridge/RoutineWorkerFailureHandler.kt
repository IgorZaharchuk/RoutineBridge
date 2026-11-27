package com.routinebridge

import com.routinebridge.rootcore.RootCoreSession

/**
 * Shared failure ordering for production and diagnostic worker operations.
 *
 * Session invalidation must happen before the command is released and
 * before caller-specific failure reporting.
 */
internal object RoutineWorkerFailureHandler {

    fun handle(
        activeSession: RootCoreSession?,
        invalidateSession: (RootCoreSession) -> Unit,
        onFailure: () -> Unit
    ) {
        activeSession?.let(invalidateSession)
        onFailure()
    }
}
