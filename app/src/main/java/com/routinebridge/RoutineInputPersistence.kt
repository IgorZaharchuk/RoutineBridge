package com.routinebridge

import android.content.Context
import android.content.pm.ShortcutManager

internal data class RoutineInputPublicationResult(
    val updated: Boolean,
    val error: String? = null
)

internal data class RoutineInputSaveResult(
    val shortcutsUpdated: Boolean,
    val shortcutError: String? = null
)

internal class RoutineInputPersistence(context: Context) {
    private val appContext = context.applicationContext
    private val store = RoutineInputStore(appContext)
    private val publisher = RoutineShortcutPublisher(appContext)
    private var loadedSuccessfully = false

    fun load(): List<RoutineInput> {
        loadedSuccessfully = false
        return try {
            store.load().also { loadedSuccessfully = true }
        } catch (error: Throwable) {
            loadedSuccessfully = false
            throw error
        }
    }

    fun publishLoaded(inputs: List<RoutineInput>): RoutineInputPublicationResult {
        check(loadedSuccessfully) {
            appContext.getString(R.string.save_blocked)
        }
        return publish(inputs, emptyList(), null)
    }

    fun save(
        previous: List<RoutineInput>,
        updated: List<RoutineInput>,
        disabledMessage: CharSequence
    ): RoutineInputSaveResult {
        check(loadedSuccessfully) {
            appContext.getString(R.string.save_blocked)
        }

        store.save(updated)

        val removedIds = previous
            .filter { old -> updated.none { it.id == old.id } }
            .map { it.id }

        val result = publish(updated, removedIds, disabledMessage)
        return RoutineInputSaveResult(
            shortcutsUpdated = result.updated,
            shortcutError = result.error
        )
    }

    private fun publish(
        inputs: List<RoutineInput>,
        removedIds: List<String>,
        disabledMessage: CharSequence?
    ): RoutineInputPublicationResult {
        val limit = appContext
            .getSystemService(ShortcutManager::class.java)
            .maxShortcutCountPerActivity

        return try {
            val updated = publisher.publishAll(
                inputs,
                removedIds,
                disabledMessage
            )

            when {
                inputs.size > limit -> RoutineInputPublicationResult(
                    updated = false,
                    error = appContext.getString(
                        R.string.shortcut_limit,
                        limit,
                        inputs.size
                    )
                )
                !updated -> RoutineInputPublicationResult(
                    updated = false,
                    error = appContext.getString(R.string.shortcut_update_failed)
                )
                else -> RoutineInputPublicationResult(updated = true)
            }
        } catch (error: Exception) {
            RoutineInputPublicationResult(
                updated = false,
                error = error.message
                    ?: appContext.getString(R.string.shortcut_update_failed)
            )
        }
    }
}
