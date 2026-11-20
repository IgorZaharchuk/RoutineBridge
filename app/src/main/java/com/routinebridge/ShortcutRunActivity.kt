package com.routinebridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import java.util.UUID

class ShortcutRunActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        executeInput(intent)
    }

    private fun openMainActivityWithError(message: String) {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_SHORTCUT_ERROR, message)
        }
        startActivity(mainIntent)
        finish()
    }

    private fun executeInput(intent: Intent?) {
        if (intent?.action != MainActivity.ACTION_RUN_INPUT) {
            finish()
            return
        }

        val id = intent.getStringExtra(MainActivity.EXTRA_INPUT_ID)
        if (id == null) {
            Log.e(LOG_TAG, "SHORTCUT_RUN_ERROR reason=input_id_missing")
            openMainActivityWithError(
                if (getSharedPreferences("settings", MODE_PRIVATE).getBoolean("english", false))
                    "Shortcut input ID is missing."
                else
                    "В ярлыке отсутствует идентификатор входа."
            )
            return
        }

        val english = getSharedPreferences("settings", MODE_PRIVATE)
            .getBoolean("english", false)

        val input = try {
            RoutineInputStore(this).load().firstOrNull { it.id == id }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "SHORTCUT_RUN_ERROR reason=settings_load_failed", e)
            val message = if (english) {
                "Could not load input settings. Open RoutineBridge to check them."
            } else {
                "Не удалось загрузить настройки входов. Проверь их в RoutineBridge."
            }
            openMainActivityWithError(message)
            return
        }

        if (input == null) {
            Log.e(LOG_TAG, "SHORTCUT_RUN_ERROR reason=input_not_found id=$id")
            val message = if (english) {
                "This input no longer exists. Open RoutineBridge to refresh shortcuts."
            } else {
                "Этот вход больше не существует. Открой RoutineBridge и обнови ярлыки."
            }
            openMainActivityWithError(message)
            return
        }

        val requestId = UUID.randomUUID().toString().take(8)

        Thread {
            var errorMessage: String? = null
            try {
                val result = RoutineActionExecutor(
                    english = english
                ).execute(input, requestId)
                Log.i(LOG_TAG, "SHORTCUT_RUN_FINISHED request=$requestId result=$result")
            } catch (e: Exception) {
                Log.e(LOG_TAG, "SHORTCUT_RUN_FAILED request=$requestId error=${e.message}", e)
                errorMessage = if (e is RootAccessException) {
                    if (english) "Root access unavailable." else "Root-доступ недоступен."
                } else {
                    e.message ?: if (english) "Action failed." else "Не удалось выполнить действие."
                }
            }

            runOnUiThread {
                if (errorMessage != null) {
                    openMainActivityWithError(errorMessage!!)
                } else {
                    finish()
                }
            }
        }.start()
    }

    companion object {
        private const val LOG_TAG = "RoutineBridge"
    }
}
