package com.routinebridge

import android.content.Context
import com.rootcore.RootCoreSession

/**
 * Worker-thread-confined cache for the RootCore session.
 *
 * A failed session is removed only if it is still the cached instance.
 */
internal class RootCoreSessionCache(
    private val openSession: (Context) -> RootCoreSession,
    private val closeSession: (RootCoreSession) -> Unit
) {
    private var session: RootCoreSession? = null

    fun get(context: Context): RootCoreSession {
        return session ?: openSession(context).also {
            session = it
        }
    }

    fun invalidateIfUnusable(failedSession: RootCoreSession): Boolean {
        if (session !== failedSession) {
            return false
        }

        return RootCoreAccess.invalidateIfUnusable(failedSession) { unusable ->
            if (session === unusable) {
                session = null
                closeSession(unusable)
            }
        }
    }

    fun close() {
        val current = session ?: return
        session = null
        closeSession(current)
    }
}
