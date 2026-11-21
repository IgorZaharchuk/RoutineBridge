package com.routinebridge

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import com.routinebridge.rootcore.RootCoreSession
import java.util.concurrent.ConcurrentHashMap

class RoutineActionExecutor(
    private val context: Context,
    private val repository: ManualRoutineRepository =
        ManualRoutineRepository(context),
) {
    private val localizedContext = AppLocale.wrap(context)

    fun execute(input: RoutineInput, requestId: String): String {
        val inputLock = inputLocks.computeIfAbsent(input.id) { Any() }
        return synchronized(inputLock) {
            val routineLock = routineLocks.computeIfAbsent(
                input.routineUuid.toString()
            ) { Any() }
            synchronized(routineLock) {
                executeLocked(input, requestId)
            }
        }
    }

    private fun executeLocked(input: RoutineInput, requestId: String): String {
        Log.i(
            LOG_TAG,
            "EXEC_START request=$requestId input=${input.id} " +
                "action=${input.action} uuid=${input.routineUuid}"
        )

        val session = RootCoreAccess.open(context)

        try {
            check(session.ping() == 0) {
                localizedContext.getString(R.string.root_core_invalid_uid)
            }

            return executeSamsungLocked(input, requestId, session)
        } finally {
            RootCoreAccess.close(session)
        }
    }

    private fun executeSamsungLocked(
        input: RoutineInput,
        requestId: String,
        session: com.routinebridge.rootcore.RootCoreSession
    ): String {
            val routine = repository.load(session).firstOrNull {
                it.uuid == input.routineUuid
            } ?: throw IllegalStateException(
                localizedContext.getString(
                    R.string.routine_not_found,
                    input.routineName
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

            val target = when (input.action) {
                RoutineInputAction.ENABLE -> true
                RoutineInputAction.DISABLE -> false
                RoutineInputAction.TOGGLE -> !current
            }

            if (current == target) {
                return localizedContext.getString(
                    R.string.routine_already_state,
                    routine.name,
                    localizedContext.getString(
                        if (target) R.string.state_enabled
                        else R.string.state_disabled
                    )
                )
            }

            val extras = Bundle().apply {
                putLong("EXTRA_KEY_ROUTINE_UUID", routine.uuid)
            }

            Log.i(LOG_TAG, "SAMSUNG_LAUNCH request=$requestId")
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

            val deadline = SystemClock.elapsedRealtime() + 5_000L
            var updated = repository.load(session).firstOrNull {
                it.uuid == routine.uuid
            }

            while (updated?.isRunning != target &&
                SystemClock.elapsedRealtime() < deadline
            ) {
                Thread.sleep(250L)
                updated = repository.load(session).firstOrNull {
                    it.uuid == routine.uuid
                }
            }

            Log.i(
                LOG_TAG,
                "STATE_AFTER request=$requestId " +
                    "expected=$target actual=${updated?.isRunning}"
            )

            if (updated?.isRunning != target) {
                val actual = updated?.isRunning?.let {
                    localizedContext.getString(
                        if (it) R.string.state_enabled
                        else R.string.state_disabled
                    )
                } ?: localizedContext.getString(R.string.state_unknown)

                val expected = localizedContext.getString(
                    if (target) R.string.state_enabled
                    else R.string.state_disabled
                )

                throw IllegalStateException(
                    localizedContext.getString(
                        R.string.routine_state_not_confirmed,
                        expected,
                        actual
                    )
                )
            }

            return localizedContext.getString(
                R.string.routine_action_success,
                routine.name,
                localizedContext.getString(
                    if (target) R.string.state_enabled
                    else R.string.state_disabled
                )
            )
    }

    private companion object {
        const val LOG_TAG = "RoutineBridge"

        val inputLocks = ConcurrentHashMap<String, Any>()
        val routineLocks = ConcurrentHashMap<String, Any>()

    }
}
