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
            val session = RootCore.open(context.applicationContext, policy, timeoutMs)
            Log.i(
                TAG,
                "ROOT_SESSION_OPEN session=${session.sessionId.take(8)}"
            )
            session
        } catch (error: Exception) {
            throw RootCoreAccessException(error)
        }
    }

    fun isUsable(session: RootCoreSession): Boolean {
        return try {
            session.isConnected()
        } catch (_: Exception) {
            false
        }
    }

    fun invalidateIfUnusable(
        session: RootCoreSession,
        onInvalidate: (RootCoreSession) -> Unit
    ): Boolean {
        if (isUsable(session)) {
            return false
        }

        onInvalidate(session)
        return true
    }

    fun close(session: RootCoreSession) {
        try {
            val sessionId = session.sessionId.take(8)

            Log.i(
                TAG,
                "ROOT_SESSION_CLOSE_BEGIN session=$sessionId"
            )

            session.close()

            Log.i(
                TAG,
                "ROOT_SESSION_CLOSE_REQUESTED session=$sessionId"
            )
        } catch (error: Exception) {
            Log.w(TAG, "Failed to close Root Core session", error)
        }
    }
}
