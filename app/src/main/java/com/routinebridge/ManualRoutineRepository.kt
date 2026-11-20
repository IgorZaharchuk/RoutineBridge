package com.routinebridge

import android.content.Context
import android.net.Uri
import android.os.Bundle
import com.routinebridge.rootcore.RootCoreSession

class ManualRoutineRepository(
    private val context: Context
) {
    private val providerUri = Uri.parse(
        "content://com.samsung.android.app.routines.externalprovider/" +
            "routine_list?routine_type=normal&condition_type=manual"
    )

    fun load(): List<ManualRoutine> {
        val session = RootCoreAccess.open(context)
        return try {
            load(session)
        } finally {
            RootCoreAccess.close(session)
        }
    }

    fun load(session: RootCoreSession): List<ManualRoutine> {
        val result = session.queryProvider(providerUri, null)

        val columns = result.getStringArray("columns")
            ?: throw IllegalStateException(
                "Samsung Provider не вернул список столбцов"
            )

        @Suppress("DEPRECATION")
        val rows = result.getParcelableArrayList<Bundle>("rows")
            ?: throw IllegalStateException(
                "Samsung Provider не вернул строки"
            )

        val uuidIndex = columns.indexOf("uuid")
        val nameIndex = columns.indexOf("name")
        val runningIndex = columns.indexOf("is_running")
        val enabledIndex = columns.indexOf("is_enabled")

        check(uuidIndex >= 0) {
            "В Samsung Provider отсутствует столбец uuid"
        }
        check(nameIndex >= 0) {
            "В Samsung Provider отсутствует столбец name"
        }

        return rows.mapIndexed { index, row ->
            val uuid = row.getString("c$uuidIndex")
                ?.toLongOrNull()
                ?: throw IllegalStateException(
                    "Samsung Provider: некорректный UUID в строке ${index + 1}"
                )

            val name = row.getString("c$nameIndex")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: throw IllegalStateException(
                    "Samsung Provider: пустое имя в строке ${index + 1}"
                )

            ManualRoutine(
                uuid = uuid,
                name = name,
                isRunning = readBoolean(row, runningIndex),
                isEnabled = readBoolean(row, enabledIndex)
            )
        }
    }

    private fun readBoolean(row: Bundle, index: Int): Boolean? {
        if (index < 0) return null

        return when (row.getString("c$index")?.trim()?.lowercase()) {
            "1", "true" -> true
            "0", "false" -> false
            else -> null
        }
    }
}
