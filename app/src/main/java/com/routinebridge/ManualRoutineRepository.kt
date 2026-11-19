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

        val rowLines = result.lineSequence()
            .filter { it.startsWith("Row:") }
            .toList()

        if (rowLines.isEmpty()) {
            if ("No result found" in result) return emptyList()

            throw IllegalStateException(
                "Не удалось разобрать ответ Samsung Provider:\n$result"
            )
        }

        return rowLines.mapIndexed { index, line ->
            val nameMatch = nameRegex.find(line)
                ?: throw IllegalStateException(
                    "Некорректная строка Samsung Provider: строка ${index + 1}, отсутствует имя"
                )

            // Исключаем имя из поиска метаданных, чтобы его текст
            // не мог подменить UUID или поля состояния.
            val metadata = line.removeRange(nameMatch.range)

            val uuid = uuidRegex.find(metadata)
                ?.groupValues?.get(1)?.toLongOrNull()
                ?: throw IllegalStateException(
                    "Некорректная строка Samsung Provider: строка ${index + 1}, неверный UUID"
                )

            val name = nameMatch.groupValues[1]
                .trim()
                .takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException(
                    "Некорректная строка Samsung Provider: строка ${index + 1}, пустое имя"
                )

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
    }
}
