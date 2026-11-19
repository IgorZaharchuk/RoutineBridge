package com.routinebridge

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon

class RoutineShortcutPublisher(private val context: Context) {
    fun publishAll(inputs: List<RoutineInput>): Boolean {
        val manager = context.getSystemService(ShortcutManager::class.java)
        if (inputs.size > manager.maxShortcutCountPerActivity) {
            throw IllegalStateException(
                "Android разрешает не более ${manager.maxShortcutCountPerActivity} динамических ярлыков"
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
