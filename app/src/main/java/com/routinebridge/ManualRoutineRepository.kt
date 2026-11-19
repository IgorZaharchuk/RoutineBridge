package com.routinebridge


class ManualRoutineRepository {

    private val authority =
        "com.samsung.android.app.routines.externalprovider"

    fun load(): List<ManualRoutine> {
        val uri = "content://$authority/routine_list" +
            "?routine_type=normal&condition_type=manual"
        val command = "content query --uri '$uri'"

        val result = RootCommand.execute(command, 10)

        val uuidRegex = Regex("""\buuid=(-?\d+)""")
        val nameRegex = Regex("""\bname=(.*?), icon_resource_id=""")
        val runningRegex = Regex("""\bis_running=([01])\b""")
        val enabledRegex = Regex("""\bis_enabled=([01])\b""")

        val rows = result.lineSequence()
            .filter { it.startsWith("Row:") }
            .mapNotNull { line ->
                val uuid = uuidRegex.find(line)
                    ?.groupValues?.get(1)?.toLongOrNull()
                    ?: return@mapNotNull null

                val nameMatch = nameRegex.find(line)
                    ?: return@mapNotNull null

                val name = nameMatch.groupValues[1]
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null

                // Не позволяем тексту имени подменять поля состояния.
                val metadata = line.removeRange(nameMatch.range)

                val running = runningRegex.find(metadata)
                    ?.groupValues?.get(1)
                    ?.let { it == "1" }

                val enabled = enabledRegex.find(metadata)
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
