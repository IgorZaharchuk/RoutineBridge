package com.routinebridge

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon

internal class RoutineShortcutPublisher(private val context: Context) {
    private val messages = AppLocale.wrap(context)

    fun publishAll(
        inputs: List<RoutineInput>,
        removedInputIds: List<String> = emptyList(),
        disabledMessage: CharSequence? = null
    ): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java)
        val activeShortcutIds = inputs.mapTo(mutableSetOf()) {
            "routine_input_${it.id}"
        }

        val pinnedShortcutsResult = runCatching {
            manager.pinnedShortcuts
        }
        val staleShortcutIds = buildSet {
            removedInputIds
                .map { "routine_input_$it" }
                .filterNot { it in activeShortcutIds }
                .forEach(::add)

            pinnedShortcutsResult.getOrDefault(emptyList())
                .map { it.id }
                .filter {
                    it.startsWith("routine_input_") &&
                        it !in activeShortcutIds
                }
                .forEach(::add)
        }

        val removedShortcutsDisabled =
            pinnedShortcutsResult.isSuccess &&
                (staleShortcutIds.isEmpty() || runCatching {
                    manager.disableShortcuts(
                        staleShortcutIds.toList(),
                        disabledMessage ?: messages.getString(
                            R.string.shortcut_input_missing
                        )
                    )
                }.isSuccess)

        val withinLimit = inputs.size <= manager.maxShortcutCountPerActivity
        val shortcuts = inputs.take(manager.maxShortcutCountPerActivity).map { input ->
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
        return manager.setDynamicShortcuts(shortcuts) && withinLimit && removedShortcutsDisabled
    }
}
