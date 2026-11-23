package com.routinebridge

import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.routinebridge.rootcore.RootCore
import com.routinebridge.rootcore.RootCorePolicy
import com.routinebridge.rootcore.RootCoreSession

internal class RootCoreAccessException(cause: Exception) : IllegalStateException(
    "Root Core недоступен: ${cause.message ?: cause.javaClass.simpleName}",
    cause
)

internal object RootCoreAccess {
    private const val TAG = "RoutineBridge"

    private val policy = RootCorePolicy.builder()
        .allowProviderAuthority(
            "com.samsung.android.app.routines.externalprovider"
        )
        .allowActivity(
            ComponentName(
                "com.samsung.android.app.routines",
                "com.samsung.android.app.routines.ui.shortcut.ShortcutLaunchActivity"
            ),
            null,
            0x10008000,
            mapOf(
                "EXTRA_KEY_ROUTINE_UUID" to RootCorePolicy.ExtraType.LONG
            )
        )
        .build()

    fun open(
        context: Context,
        timeoutMs: Long = 15_000L
    ): RootCoreSession {
        return try {
            RootCore.open(context.applicationContext, policy, timeoutMs)
        } catch (error: Exception) {
            throw RootCoreAccessException(error)
        }
    }

    fun isUsable(session: RootCoreSession): Boolean {
        return try {
            session.isRootProcessAlive() && session.ping() == 0
        } catch (_: Exception) {
            false
        }
    }

    fun close(session: RootCoreSession) {
        try {
            session.close()

            if (!session.awaitTermination(2_000L)) {
                Log.w(TAG, "Root Core process did not terminate within 2 seconds")
            }
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            Log.w(TAG, "Interrupted while waiting for Root Core termination", error)
        } catch (error: Exception) {
            Log.w(TAG, "Failed to close Root Core session", error)
        }
    }
}
