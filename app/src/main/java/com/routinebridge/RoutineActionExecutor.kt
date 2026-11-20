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
    private val english: Boolean = false
) {
    fun execute(input: RoutineInput, requestId: String): String {
        val lock = routineLocks.computeIfAbsent(input.routineUuid.toString()) { Any() }
        return synchronized(lock) {
            executeLocked(input, requestId)
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
                "Root Core работает не с UID 0"
            }

            val routine = repository.load(session).firstOrNull {
                it.uuid == input.routineUuid
            } ?: throw IllegalStateException(
                message(
                    "Сценарий «${input.routineName}» не найден",
                    "Routine “${input.routineName}” not found"
                )
            )

            when (routine.isEnabled) {
                false -> throw IllegalStateException(
                    message(
                        "Сценарий «${routine.name}» отключён в Samsung",
                        "Routine “${routine.name}” is disabled in Samsung"
                    )
                )
                null -> throw IllegalStateException(
                    message(
                        "Не удалось определить, включён ли сценарий в Samsung",
                        "Could not determine whether the routine is enabled in Samsung"
                    )
                )
                true -> Unit
            }

            val current = routine.isRunning ?: throw IllegalStateException(
                message(
                    "Не удалось определить состояние сценария",
                    "Could not determine routine state"
                )
            )

            val target = when (input.action) {
                RoutineInputAction.ENABLE -> true
                RoutineInputAction.DISABLE -> false
                RoutineInputAction.TOGGLE -> !current
            }

            if (current == target) {
                return if (english) {
                    "Routine “${routine.name}” is already " +
                        if (target) "enabled" else "disabled"
                } else {
                    "Сценарий «${routine.name}» уже " +
                        if (target) "включён" else "выключен"
                }
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
                    if (it) {
                        message("включён", "enabled")
                    } else {
                        message("выключен", "disabled")
                    }
                } ?: message("неизвестно", "unknown")

                throw IllegalStateException(
                    message(
                        "Новое состояние сценария не подтверждено. " +
                            "Ожидалось: ${if (target) "включён" else "выключен"}; " +
                            "получено: $actual",
                        "The new routine state was not confirmed. " +
                            "Expected: ${if (target) "enabled" else "disabled"}; " +
                            "actual: $actual"
                    )
                )
            }

            return if (english) {
                "Routine “${routine.name}”: ${if (target) "enabled" else "disabled"}"
            } else {
                "Сценарий «${routine.name}»: ${if (target) "включён" else "выключен"}"
            }
        } finally {
            RootCoreAccess.close(session)
        }
    }

    private fun message(ru: String, en: String): String =
        if (english) en else ru

    private companion object {
        const val LOG_TAG = "RoutineBridge"
        val routineLocks = ConcurrentHashMap<String, Any>()
    }
}
