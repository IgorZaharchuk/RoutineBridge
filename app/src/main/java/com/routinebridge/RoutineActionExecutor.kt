package com.routinebridge

import java.util.concurrent.TimeUnit

class RoutineActionExecutor(
    private val repository: ManualRoutineRepository = ManualRoutineRepository()
) {
    fun execute(input: RoutineInput): String {
        val routine = repository.load().firstOrNull { it.uuid == input.routineUuid }
            ?: throw IllegalStateException("Сценарий «${input.routineName}» не найден")

        if (routine.isEnabled == false) {
            throw IllegalStateException("Сценарий «${routine.name}» отключён в Samsung")
        }

        val current = routine.isRunning
            ?: throw IllegalStateException("Не удалось определить состояние сценария")

        val target = when (input.action) {
            RoutineInputAction.ENABLE -> true
            RoutineInputAction.DISABLE -> false
            RoutineInputAction.TOGGLE -> !current
        }

        if (current == target) {
            return "Сценарий «${routine.name}» уже ${if (target) "включён" else "выключен"}"
        }

        val command = "am start -W -n " +
            "com.samsung.android.app.routines/.ui.shortcut.ShortcutLaunchActivity " +
            "--es EXTRA_KEY_ROUTINE_NAME ${quote(routine.name)} " +
            "--el EXTRA_KEY_ROUTINE_UUID ${routine.uuid} -f 0x10008000"

        val output = runRoot(command)
        if (output.contains("Error:", true) || output.contains("Exception", true)) {
            throw IllegalStateException("Samsung вернул ошибку:\n$output")
        }

        Thread.sleep(900)
        val updated = repository.load().firstOrNull { it.uuid == routine.uuid }
        if (updated?.isRunning != target) {
            throw IllegalStateException(
                "Новое состояние сценария не подтверждено. Ответ: $output"
            )
        }

        return "Сценарий «${routine.name}»: ${if (target) "включён" else "выключен"}"
    }

    private fun runRoot(command: String): String {
        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true).start()
        val output = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines {
                output.append(it.joinToString("\n"))
            }
        }
        reader.start()
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(500)
            throw IllegalStateException("Превышено время ожидания Root-команды")
        }
        reader.join(1000)
        if (process.exitValue() != 0) {
            throw IllegalStateException("Root завершился с кодом ${process.exitValue()}:\n$output")
        }
        return output.toString()
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
