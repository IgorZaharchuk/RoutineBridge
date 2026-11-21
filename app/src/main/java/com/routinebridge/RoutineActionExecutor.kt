package com.routinebridge

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log

internal enum class RoutineExecutionStatus {
    SUCCESS,
    SKIPPED,
    TIMEOUT
}

internal data class RoutineExecutionResult(
    val status: RoutineExecutionStatus,
    val routineUuid: Long,
    val routineName: String,
    val targetState: Boolean,
    val actualState: Boolean?
)

internal class RoutineActionExecutor(
    context: Context,
    private val repository: ManualRoutineRepository =
        ManualRoutineRepository(context)
) {
    private val appContext = context.applicationContext
    private val localizedContext = AppLocale.wrap(context)

    /**
     * Performs exactly one physical Samsung launch operation.
     *
     * Queueing, coalescing and serialization belong to the dispatcher.
     * This class does not debounce, lock UUIDs or retry timed-out launches.
     */
    fun executeOnce(
        routineUuid: Long,
        targetState: Boolean,
        requestId: String
    ): RoutineExecutionResult {
        Log.i(
            LOG_TAG,
            "EXEC_ONCE_START request=$requestId uuid=$routineUuid target=$targetState"
        )

        val session = RootCoreAccess.open(appContext)

        try {
            check(session.ping() == 0) {
                localizedContext.getString(R.string.root_core_invalid_uid)
            }

            val routine = repository.load(session).firstOrNull {
                it.uuid == routineUuid
            } ?: throw IllegalStateException(
                localizedContext.getString(
                    R.string.routine_not_found,
                    routineUuid.toString()
                )
            )

            when (routine.isEnabled) {
                false -> throw IllegalStateException(
                    localizedContext.getString(
                        R.string.routine_disabled,
                        routine.name
                    )
                )
                null -> throw IllegalStateException(
                    localizedContext.getString(
                        R.string.routine_enabled_state_unknown
                    )
                )
                true -> Unit
            }

            val current = routine.isRunning ?: throw IllegalStateException(
                localizedContext.getString(R.string.routine_state_unknown)
            )

            if (current == targetState) {
                Log.i(
                    LOG_TAG,
                    "EXEC_ONCE_SKIPPED request=$requestId uuid=$routineUuid state=$current"
                )
                return RoutineExecutionResult(
                    status = RoutineExecutionStatus.SKIPPED,
                    routineUuid = routineUuid,
                    routineName = routine.name,
                    targetState = targetState,
                    actualState = current
                )
            }

            val extras = Bundle().apply {
                putLong("EXTRA_KEY_ROUTINE_UUID", routineUuid)
            }

            Log.i(LOG_TAG, "SAMSUNG_LAUNCH request=$requestId uuid=$routineUuid")
            val launchStart = SystemClock.elapsedRealtime()

            session.launchActivity(
                ComponentName(
                    "com.samsung.android.app.routines",
                    "com.samsung.android.app.routines.ui.shortcut.ShortcutLaunchActivity"
                ),
                null,
                extras,
                0x10008000
            )

            Log.i(
                LOG_TAG,
                "SAMSUNG_RETURN request=$requestId " +
                    "elapsed=${SystemClock.elapsedRealtime() - launchStart}ms"
            )

            val deadline = SystemClock.elapsedRealtime() + CONFIRMATION_TIMEOUT_MS
            var actual = repository.load(session).firstOrNull {
                it.uuid == routineUuid
            }?.isRunning

            while (actual != targetState &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                Thread.sleep(PROVIDER_POLL_INTERVAL_MS)
                actual = repository.load(session).firstOrNull {
                    it.uuid == routineUuid
                }?.isRunning
            }

            if (actual == targetState) {
                Log.i(
                    LOG_TAG,
                    "EXEC_ONCE_SUCCESS request=$requestId uuid=$routineUuid state=$actual"
                )
                return RoutineExecutionResult(
                    status = RoutineExecutionStatus.SUCCESS,
                    routineUuid = routineUuid,
                    routineName = routine.name,
                    targetState = targetState,
                    actualState = actual
                )
            }

            Log.w(
                LOG_TAG,
                "EXEC_ONCE_TIMEOUT request=$requestId uuid=$routineUuid " +
                    "expected=$targetState actual=$actual"
            )

            return RoutineExecutionResult(
                status = RoutineExecutionStatus.TIMEOUT,
                routineUuid = routineUuid,
                routineName = routine.name,
                targetState = targetState,
                actualState = actual
            )
        } finally {
            RootCoreAccess.close(session)
        }
    }

    private companion object {
        const val LOG_TAG = "RoutineBridge"
        const val PROVIDER_POLL_INTERVAL_MS = 100L
        const val CONFIRMATION_TIMEOUT_MS = 1_500L
    }
}
