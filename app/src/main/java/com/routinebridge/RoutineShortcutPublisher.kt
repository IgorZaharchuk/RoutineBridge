package com.routinebridge

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon

class RoutineShortcutPublisher(private val context: Context) {
    private val messages = AppLocale.wrap(context)

    fun publishAll(
        inputs: List<RoutineInput>,
        removedInputIds: List<String> = emptyList(),
        disabledMessage: CharSequence? = null
    ): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java)
        if (inputs.size > manager.maxShortcutCountPerActivity) {
            throw IllegalStateException(
                messages.getString(
                    R.string.dynamic_shortcut_limit,
                    manager.maxShortcutCountPerActivity
                )
            )
        }

        if (removedInputIds.isNotEmpty()) {
            manager.disableShortcuts(
                removedInputIds.map { "routine_input_$it" },
                disabledMessage ?: messages.getString(
                    R.string.shortcut_input_missing
                )
            )
        }

        val shortcuts = inputs.map { input ->
            val intent = Intent(context, ShortcutRunActivity::class.java).apply {
                action = MainActivity.ACTION_RUN_INPUT
                putExtra(MainActivity.EXTRA_INPUT_ID, input.id)
            }
            ShortcutInfo.Builder(context, "routine_input_${input.id}")
                .setShortLabel(input.label.take(25))
                .setLongLabel(input.label)
                .setIcon(Icon.createWithResource(context, R.mipmap.ic_launcher))
                .setIntent(intent)
                .build()
        }
        return manager.setDynamicShortcuts(shortcuts)
    }
}
