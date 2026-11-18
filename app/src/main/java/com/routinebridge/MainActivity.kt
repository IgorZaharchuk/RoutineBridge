package com.routinebridge

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private val routinesPackage = "com.samsung.android.app.routines"
    private val authority = "com.samsung.android.app.routines.externalprovider"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        val output = TextView(this).apply {
            textSize = 14f
            text = "RoutineBridge 0.1.0\n\nНажми «Проверить систему»."
        }

        val button = Button(this).apply {
            text = "Проверить систему"
            setOnClickListener {
                output.text = diagnose()
            }
        }

        content.addView(button)
        content.addView(output)

        setContentView(ScrollView(this).apply {
            addView(content)
        })
    }

    private fun diagnose(): String {
        val result = StringBuilder()
        result.appendLine("RoutineBridge 0.1.0")
        result.appendLine("Диагностика Samsung Modes and Routines")
        result.appendLine()

        try {
            val info = packageManager.getPackageInfo(
                routinesPackage,
                PackageManager.GET_ACTIVITIES or
                    PackageManager.GET_PROVIDERS or
                    PackageManager.GET_PERMISSIONS
            )

            result.appendLine("ПАКЕТ: найден")
            result.appendLine("Версия: ${info.versionName}")
            result.appendLine("Имя: ${info.packageName}")
            result.appendLine()

            result.appendLine("ACTIVITY:")
            val activities = info.activities.orEmpty()
            if (activities.isEmpty()) {
                result.appendLine("Список не получен")
            } else {
                activities.forEach {
                    result.appendLine(it.name)
                    result.appendLine("  exported=${it.exported}")
                }
            }

            result.appendLine()
            result.appendLine("CONTENT PROVIDERS:")
            val providers = info.providers.orEmpty()
            if (providers.isEmpty()) {
                result.appendLine("Список не получен")
            } else {
                providers.forEach {
                    result.appendLine(it.name)
                    result.appendLine("  authority=${it.authority}")
                    result.appendLine("  exported=${it.exported}")
                    result.appendLine("  readPermission=${it.readPermission}")
                    result.appendLine("  writePermission=${it.writePermission}")
                }
            }

            result.appendLine()
            result.appendLine("РАЗРЕШЕНИЯ ПАКЕТА:")
            info.requestedPermissions.orEmpty().forEach {
                result.appendLine(it)
            }

            result.appendLine()
            result.appendLine("ПРОВЕРКА ДОСТУПА:")
            try {
                val permission = "com.samsung.android.app.routines.permission.READ_ROUTINE_INFO"
                val granted = checkSelfPermission(permission) ==
                    PackageManager.PERMISSION_GRANTED
                result.appendLine("READ_ROUTINE_INFO granted=$granted")
            } catch (e: Exception) {
                result.appendLine("Ошибка: ${e.message}")
            }

            result.appendLine()
            result.appendLine("Provider authority:")
            result.appendLine(authority)
            result.appendLine("Сам факт обнаружения не означает доступность чтения.")

        } catch (e: PackageManager.NameNotFoundException) {
            result.appendLine("ПАКЕТ НЕ НАЙДЕН")
        } catch (e: Exception) {
            result.appendLine("ОШИБКА: ${e.javaClass.simpleName}")
            result.appendLine(e.message ?: "")
        }

        return result.toString()
    }
}
