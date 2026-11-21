package com.routinebridge

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

internal object AppLocale {
    fun isEnglish(context: Context): Boolean =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)
            .getBoolean("english", false)

    fun wrap(context: Context): Context {
        val locale = if (isEnglish(context)) Locale.ENGLISH else Locale("ru")
        val configuration = Configuration(context.resources.configuration)
        configuration.setLocale(locale)
        return context.createConfigurationContext(configuration)
    }
}
