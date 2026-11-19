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

    private fun executeInput(intent: Intent?) {
        if (intent?.action != MainActivity.ACTION_RUN_INPUT) {
            finish()
            return
        }

        val id = intent.getStringExtra(MainActivity.EXTRA_INPUT_ID)
        if (id == null) {
            Log.e(LOG_TAG, "SHORTCUT_RUN_ERROR reason=input_id_missing")
            finish()
            return
        }

        val input = RoutineInputStore(this).load().firstOrNull { it.id == id }
        if (input == null) {
            Log.e(LOG_TAG, "SHORTCUT_RUN_ERROR reason=input_not_found id=$id")
            finish()
            return
        }

        val requestId = UUID.randomUUID().toString().take(8)

        Thread {
            try {
                val result = RoutineActionExecutor(
                    english = getSharedPreferences("settings", MODE_PRIVATE)
                        .getBoolean("english", false)
                ).execute(input, requestId)
                Log.i(LOG_TAG, "SHORTCUT_RUN_FINISHED request=$requestId result=$result")
            } catch (e: Exception) {
                Log.e(LOG_TAG, "SHORTCUT_RUN_FAILED request=$requestId error=${e.message}", e)
                val english = getSharedPreferences("settings", MODE_PRIVATE)
                    .getBoolean("english", false)
                val message = if (e is RootAccessException) {
                    if (english) "Root access unavailable." else "Root-доступ недоступен."
                } else {
                    e.message ?: if (english) "Action failed." else "Не удалось выполнить действие."
                }
                runOnUiThread {
                    android.widget.Toast.makeText(
                        this@ShortcutRunActivity, message, android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                runOnUiThread { finish() }
            }
        }.start()
    }

    companion object {
        private const val LOG_TAG = "RoutineBridge"
    }
}
