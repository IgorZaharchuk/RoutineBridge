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
            "SHORTCUT_ACTIVITY_CREATE request=${intent?.getStringExtra(EXTRA_REQUEST_ID)} " +
                "action=${intent?.action} " +
                "inputId=${intent?.getStringExtra(MainActivity.EXTRA_INPUT_ID)} " +
                "component=${intent?.component?.flattenToShortString()}"
        )
        enqueueInput(intent)
    }

    private fun openMainActivityWithError(message: String) {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_SHORTCUT_ERROR, message)
        }
        startActivity(mainIntent)
        finish()
    }

    private fun enqueueInput(intent: Intent?) {
        if (intent?.action != MainActivity.ACTION_RUN_INPUT) {
            Log.e(LOG_TAG, "SHORTCUT_INTENT_REJECTED action=${intent?.action}")
            finish()
            return
        }

        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
            ?.takeIf { it.isNotBlank() }
            ?: UUID.randomUUID().toString().take(8)

        val id = intent.getStringExtra(MainActivity.EXTRA_INPUT_ID)
        if (id == null) {
            Log.e(LOG_TAG, "SHORTCUT_RUN_ERROR request=$requestId input_id_missing")
            openMainActivityWithError(getString(R.string.shortcut_id_missing))
            return
        }

        Log.i(LOG_TAG, "SHORTCUT_INPUT_LOAD_START request=$requestId inputId=$id")

        val input = try {
            RoutineInputStore(this).load().firstOrNull { it.id == id }
        } catch (error: Exception) {
            Log.e(
                LOG_TAG,
                "SHORTCUT_RUN_ERROR request=$requestId settings_load_failed",
                error
            )
            openMainActivityWithError(getString(R.string.shortcut_settings_load_failed))
            return
        }

        if (input == null) {
            Log.e(LOG_TAG, "SHORTCUT_RUN_ERROR request=$requestId input_not_found id=$id")
            openMainActivityWithError(getString(R.string.shortcut_input_missing))
            return
        }

        Log.i(
            LOG_TAG,
            "SHORTCUT_INPUT_LOADED request=$requestId inputId=${input.id} " +
                "uuid=${input.routineUuid} action=${input.action}"
        )

        Log.i(
            LOG_TAG,
            "SHORTCUT_SERVICE_START request=$requestId inputId=${input.id} " +
                "routineUuid=${input.routineUuid} action=${input.action}"
        )

        val serviceIntent = Intent(this, SamsungRoutineService::class.java).apply {
            putExtra(SamsungRoutineService.EXTRA_INPUT_ID, input.id)
            putExtra(SamsungRoutineService.EXTRA_LABEL, input.label)
            putExtra(SamsungRoutineService.EXTRA_ROUTINE_UUID, input.routineUuid)
            putExtra(SamsungRoutineService.EXTRA_ROUTINE_NAME, input.routineName)
            putExtra(SamsungRoutineService.EXTRA_ACTION, input.action.name)
            putExtra(SamsungRoutineService.EXTRA_REQUEST_ID, requestId)
        }

        try {
            startForegroundService(serviceIntent)
            finish()
        } catch (error: Exception) {
            Log.e(LOG_TAG, "SHORTCUT_SERVICE_START_FAILED request=$requestId", error)
            openMainActivityWithError(
                error.message ?: getString(R.string.action_failed)
            )
        }
    }

    companion object {
        private const val LOG_TAG = "RoutineBridge"
        const val EXTRA_REQUEST_ID = "routinebridge_request_id"
    }
}
