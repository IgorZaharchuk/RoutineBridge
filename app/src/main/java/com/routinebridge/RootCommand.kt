package com.routinebridge

import java.io.IOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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

        val outputReader = FutureTask<String> {
            process.inputStream.bufferedReader().use { it.readText() }
        }

        Thread(outputReader, "RootCommand-output").apply {
            isDaemon = true
            start()
        }

        try {
            process.outputStream.close()
        } catch (e: IOException) {
            process.destroyForcibly()
            outputReader.cancel(true)
            throw IllegalStateException("Не удалось закрыть stdin root-команды", e)
        }

        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(1, TimeUnit.SECONDS)
            runCatching { process.inputStream.close() }
            outputReader.cancel(true)
            throw IllegalStateException("Root-команда превысила таймаут")
        }

        val output = try {
            outputReader.get(2, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            throw IllegalStateException(
                "Не удалось прочитать вывод root-команды",
                e.cause
            )
        } catch (e: TimeoutException) {
            runCatching { process.inputStream.close() }
            outputReader.cancel(true)
            throw IllegalStateException(
                "Не удалось завершить чтение вывода root-команды",
                e
            )
        }

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
