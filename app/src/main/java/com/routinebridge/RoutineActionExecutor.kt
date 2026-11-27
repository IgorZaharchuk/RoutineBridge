package com.routinebridge

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log

import com.rootcore.RootCoreSession

internal enum class RoutineExecutionStatus {
    SUCCESS,
    SKIPPED,
    TIMEOUT
}

internal data class RoutineExecutionResult(
    val status: RoutineExecutionStatus,
    val routineUuid: Long,
    val routineName: String,
    val initialState: Boolean,
    val targetState: Boolean,
    val actualState: Boolean?
)

internal class RoutineActionExecutor(
    context: Context,
    private val repository: ManualRoutineRepository =
        ManualRoutineRepository(context)
) {
    private val localizedContext = AppLocale.wrap(context)

    /**
     * Performs exactly one physical Samsung launch operation.
     *
     * Queueing, coalescing and serialization belong to the dispatcher.
     * This class does not debounce, lock UUIDs or retry timed-out launches.
     */
    fun executeOnce(
        session: RootCoreSession,
        routineUuid: Long,
        transform: BooleanStateTransform,
        requestIds: List<String>,
        onStateRead: (Boolean) -> Unit
    ): RoutineExecutionResult {
        Log.i(
            LOG_TAG,
            "EXEC_ONCE_START request=${requestIds.firstOrNull() ?: "unknown"} related=${requestIds.drop(1).joinToString(",")} uuid=$routineUuid transform=$transform"
        )

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
        onStateRead(current)
        val targetState = transform.applyTo(current)

        if (current == targetState) {
            Log.i(
                LOG_TAG,
                "EXEC_ONCE_SKIPPED request=${requestIds.firstOrNull() ?: "unknown"} related=${requestIds.drop(1).joinToString(",")} uuid=$routineUuid state=$current"
            )
            return RoutineExecutionResult(
                status = RoutineExecutionStatus.SKIPPED,
                routineUuid = routineUuid,
                routineName = routine.name,
                initialState = current,
                targetState = targetState,
                actualState = current
            )
        }

        val extras = Bundle().apply {
            putLong("EXTRA_KEY_ROUTINE_UUID", routineUuid)
        }

        Log.i(LOG_TAG, "SAMSUNG_LAUNCH request=${requestIds.firstOrNull() ?: "unknown"} related=${requestIds.drop(1).joinToString(",")} uuid=$routineUuid")
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
            "SAMSUNG_RETURN request=${requestIds.firstOrNull() ?: "unknown"} " +
                "elapsed=${SystemClock.elapsedRealtime() - launchStart}ms"
        )

        val deadline = SystemClock.elapsedRealtime() + CONFIRMATION_TIMEOUT_MS
        var providerReadFailed = false

        fun readActualState(): Boolean? {
            return try {
                repository.load(session).firstOrNull {
                    it.uuid == routineUuid
                }?.isRunning
            } catch (error: Exception) {
                if (!providerReadFailed) {
                    providerReadFailed = true
                    Log.w(
                        LOG_TAG,
                        "PROVIDER_CONFIRMATION_READ_FAILED " +
                            "request=${requestIds.firstOrNull() ?: "unknown"} " +
                            "uuid=$routineUuid",
                        error
                    )
                }
                null
            }
        }

        var actual = readActualState()

        while (actual != targetState &&
            SystemClock.elapsedRealtime() < deadline
        ) {
            Thread.sleep(PROVIDER_POLL_INTERVAL_MS)
            actual = readActualState()
        }

        if (actual == targetState) {
            Log.i(
                LOG_TAG,
                "EXEC_ONCE_SUCCESS request=${requestIds.firstOrNull() ?: "unknown"} related=${requestIds.drop(1).joinToString(",")} uuid=$routineUuid state=$actual"
            )
            return RoutineExecutionResult(
                status = RoutineExecutionStatus.SUCCESS,
                routineUuid = routineUuid,
                routineName = routine.name,
                initialState = current,
                targetState = targetState,
                actualState = actual
            )
        }

        Log.w(
            LOG_TAG,
            "EXEC_ONCE_TIMEOUT request=${requestIds.firstOrNull() ?: "unknown"} " +
                "expected=$targetState actual=$actual"
        )

        return RoutineExecutionResult(
            status = RoutineExecutionStatus.TIMEOUT,
            routineUuid = routineUuid,
            routineName = routine.name,
            initialState = current,
            targetState = targetState,
            actualState = actual
        )

    }

    private companion object {
        const val LOG_TAG = "RoutineBridge"
        const val PROVIDER_POLL_INTERVAL_MS = 100L
        const val CONFIRMATION_TIMEOUT_MS = 1_500L
    }
}
