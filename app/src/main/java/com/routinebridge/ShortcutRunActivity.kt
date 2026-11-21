package com.routinebridge

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log
import java.util.UUID

class ShortcutRunActivity : Activity() {

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(
            LOG_TAG,
            "SHORTCUT_ACTIVITY_CREATE action=${intent?.action} " +
                "inputId=${intent?.getStringExtra(MainActivity.EXTRA_INPUT_ID)} " +
                "component=${intent?.component?.flattenToShortString()}"
        )
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
            Log.e(
                LOG_TAG,
                "SHORTCUT_INTENT_REJECTED action=${intent?.action} " +
                    "inputId=${intent?.getStringExtra(MainActivity.EXTRA_INPUT_ID)}"
            )
            finish()
            return
        }

        val id = intent.getStringExtra(MainActivity.EXTRA_INPUT_ID)
        if (id == null) {
            Log.e(LOG_TAG, "SHORTCUT_RUN_ERROR reason=input_id_missing")
            openMainActivityWithError(getString(R.string.shortcut_id_missing))
            return
        }

        val input = try {
            RoutineInputStore(this).load().firstOrNull { it.id == id }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "SHORTCUT_RUN_ERROR reason=settings_load_failed", e)
            openMainActivityWithError(
                getString(R.string.shortcut_settings_load_failed)
            )
            return
        }

        if (input == null) {
            Log.e(LOG_TAG, "SHORTCUT_RUN_ERROR reason=input_not_found id=$id")
            openMainActivityWithError(
                getString(R.string.shortcut_input_missing)
            )
            return
        }

        val requestId = UUID.randomUUID().toString().take(8)
        Log.i(
            LOG_TAG,
            "SHORTCUT_EXECUTE_START request=$requestId inputId=${input.id} " +
                "routineUuid=${input.routineUuid} action=${input.action}"
        )

        Thread {
            var errorMessage: String? = null
            try {
                val result = RoutineActionExecutor(
                    context = applicationContext
                ).execute(input, requestId)
                Log.i(LOG_TAG, "SHORTCUT_RUN_FINISHED request=$requestId result=$result")
            } catch (e: Exception) {
                Log.e(LOG_TAG, "SHORTCUT_RUN_FAILED request=$requestId error=${e.message}", e)
                errorMessage = if (e is RootCoreAccessException) {
                    getString(R.string.root_access_unavailable)
                } else {
                    e.message ?: getString(R.string.action_failed)
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
