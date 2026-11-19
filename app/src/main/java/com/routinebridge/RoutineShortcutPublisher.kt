package com.routinebridge

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon

class RoutineShortcutPublisher(
    private val context: Context
) {

    fun publish(
        shortcutId: String,
        label: String,
        intent: Intent
    ): Boolean {
        val manager = context.getSystemService(
            ShortcutManager::class.java
        )

        val shortcut = ShortcutInfo.Builder(context, shortcutId)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(
                Icon.createWithResource(
                    context,
                    android.R.drawable.ic_menu_view
                )
            )
            .setIntent(intent)
            .build()

        return manager.addDynamicShortcuts(listOf(shortcut))
    }
}
