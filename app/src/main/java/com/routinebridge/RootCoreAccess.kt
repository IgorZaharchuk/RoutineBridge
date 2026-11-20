package com.routinebridge

import android.content.Context
import android.util.Log
import com.routinebridge.rootcore.RootCore
import com.routinebridge.rootcore.RootCoreSession

internal class RootCoreAccessException(cause: Exception) : IllegalStateException(
    "Root Core недоступен: ${cause.message ?: cause.javaClass.simpleName}",
    cause
)

internal object RootCoreAccess {
    private const val TAG = "RoutineBridge"

    fun open(context: Context): RootCoreSession {
        return try {
            RootCore.open(context.applicationContext)
        } catch (error: Exception) {
            throw RootCoreAccessException(error)
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
