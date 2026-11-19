package com.routinebridge

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

class RoutineActionExecutor(
    private val repository: ManualRoutineRepository = ManualRoutineRepository(),
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
            "EXEC_START request=$requestId input=${input.id} action=${input.action} uuid=${input.routineUuid}"
        )

        val routine = repository.load().firstOrNull {
            it.uuid == input.routineUuid
        } ?: throw IllegalStateException(
            message(
                "Сценарий «${input.routineName}» не найден",
                "Routine “${input.routineName}” not found"
            )
        )

        if (routine.isEnabled == false) {
            throw IllegalStateException(
                message(
                    "Сценарий «${routine.name}» отключён в Samsung",
                    "Routine “${routine.name}” is disabled in Samsung"
                )
            )
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
                "Routine “${routine.name}” is already ${if (target) "enabled" else "disabled"}"
            } else {
                "Сценарий «${routine.name}» уже ${if (target) "включён" else "выключен"}"
            }
        }

        val command = "am start -W -n " +
            "com.samsung.android.app.routines/.ui.shortcut.ShortcutLaunchActivity " +
            "--el EXTRA_KEY_ROUTINE_UUID ${routine.uuid} -f 0x10008000"

        Log.i(LOG_TAG, "SAMSUNG_LAUNCH request=$requestId")
        val output = runRoot(command)
        Log.i(LOG_TAG, "SAMSUNG_RETURN request=$requestId output=${output.take(500)}")

        Thread.sleep(900)

        val updated = repository.load().firstOrNull {
            it.uuid == routine.uuid
        }

        Log.i(
            LOG_TAG,
            "STATE_AFTER request=$requestId expected=$target actual=${updated?.isRunning}"
        )

        if (updated?.isRunning != target) {
            throw IllegalStateException(
                message(
                    "Новое состояние сценария не подтверждено. Ответ: $output",
                    "The new routine state was not confirmed. Response: $output"
                )
            )
        }

        return if (english) {
            "Routine “${routine.name}”: ${if (target) "enabled" else "disabled"}"
        } else {
            "Сценарий «${routine.name}»: ${if (target) "включён" else "выключен"}"
        }
    }

    private fun message(ru: String, en: String): String =
        if (english) en else ru

    private fun runRoot(command: String): String =
        RootCommand.execute(command, 15)

    private companion object {
        const val LOG_TAG = "RoutineBridge"
        val routineLocks = ConcurrentHashMap<String, Any>()
    }
}
