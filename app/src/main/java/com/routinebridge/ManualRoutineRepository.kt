package com.routinebridge

import java.util.concurrent.TimeUnit

class ManualRoutineRepository {

    private val authority =
        "com.samsung.android.app.routines.externalprovider"

    fun load(): List<ManualRoutine> {
        val uri = "content://$authority/routine_list" +
            "?routine_type=normal&condition_type=manual"
        val command = "content query --uri '$uri'"

        val process = ProcessBuilder("su", "-c", command)
            .redirectErrorStream(true)
            .start()

        val captured = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                captured.append(lines.joinToString("\n"))
            }
        }
        reader.start()

        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(500)
            throw IllegalStateException(
                "Root-команда превысила таймаут 10 секунд"
            )
        }

        reader.join(1000)

        val result = captured.toString()
        val exitCode = process.exitValue()

        if (exitCode != 0) {
            throw IllegalStateException(
                "Команда завершилась с кодом $exitCode:\n$result"
            )
        }

        val uuidRegex = Regex("""\buuid=(\d+)""")
        val nameRegex = Regex("""\bname=(.*?), icon_resource_id=""")
        val runningRegex = Regex("""\bis_running=([01])\b""")
        val enabledRegex = Regex("""\bis_enabled=([01])\b""")

        val rows = result.lineSequence()
            .filter { it.startsWith("Row:") }
            .mapNotNull { line ->
                val uuid = uuidRegex.find(line)
                    ?.groupValues?.get(1)?.toLongOrNull()
                    ?: return@mapNotNull null

                val name = nameRegex.find(line)
                    ?.groupValues?.get(1)?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: "Без названия"

                val running = runningRegex.find(line)
                    ?.groupValues?.get(1)
                    ?.let { it == "1" }

                val enabled = enabledRegex.find(line)
                    ?.groupValues?.get(1)
                    ?.let { it == "1" }

                ManualRoutine(
                    uuid = uuid,
                    name = name,
                    isRunning = running,
                    isEnabled = enabled
                )
            }
            .toList()

        if (rows.isEmpty() && "No result found" !in result) {
            throw IllegalStateException(
                "Не удалось разобрать ответ Samsung Provider:\n$result"
            )
        }

        return rows
    }
}
