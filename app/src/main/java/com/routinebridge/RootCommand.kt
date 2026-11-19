package com.routinebridge

import java.io.IOException
import java.util.concurrent.TimeUnit

class RootAccessException : IllegalStateException()

object RootCommand {
    fun execute(command: String, timeoutSeconds: Long): String {
        val process = try {
            ProcessBuilder("su", "-c", command)
                .redirectErrorStream(true)
                .start()
        } catch (e: IOException) {
            throw RootAccessException()
        }

        val captured = StringBuilder()
        val reader = Thread {
            process.inputStream.bufferedReader().useLines { lines ->
                captured.append(lines.joinToString("\n"))
            }
        }
        reader.start()

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            reader.join(500)
            throw IllegalStateException("Root-команда превысила таймаут")
        }

        reader.join(1000)
        val output = captured.toString()
        val exitCode = process.exitValue()

        if (exitCode != 0) {
            val denied = Regex(
                "permission denied|request denied|access denied|root access denied|not allowed|su: not found|su: inaccessible",
                RegexOption.IGNORE_CASE
            ).containsMatchIn(output)

            if (denied) throw RootAccessException()
            throw IllegalStateException(
                "Команда завершилась с кодом $exitCode:\n$output"
            )
        }

        return output
    }
}
