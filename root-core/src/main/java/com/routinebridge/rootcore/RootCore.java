package com.routinebridge.rootcore;

import android.content.Context;
import android.os.Looper;

import java.util.concurrent.TimeoutException;

public final class RootCore {

    private RootCore() {}

    public static RootCoreSession open(Context context) throws Exception {
        return open(context, RootCorePolicy.denyAll(), 15_000L);
    }

    public static RootCoreSession open(
            Context context,
            long timeoutMs
    ) throws Exception {
        return open(context, RootCorePolicy.denyAll(), timeoutMs);
    }

    public static RootCoreSession open(
            Context context,
            RootCorePolicy policy
    ) throws Exception {
        return open(context, policy, 15_000L);
    }

    public static RootCoreSession open(
            Context context,
            RootCorePolicy policy,
            long timeoutMs
    ) throws Exception {
        if (context == null) {
            throw new IllegalArgumentException("Context is null");
        }
        if (policy == null) {
            throw new IllegalArgumentException("RootCore policy is null");
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("RootCore.open() must not run on the main thread");
        }
        if (timeoutMs < 1_000L) {
            throw new IllegalArgumentException("Timeout must be at least 1000 ms");
        }

        return RootCoreSession.open(
                context.getApplicationContext(),
                timeoutMs,
                policy
        );
    }
}
