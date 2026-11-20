package com.routinebridge.rootcore;

import android.content.Context;
import android.os.Looper;

import java.util.concurrent.TimeoutException;

public final class RootCore {

    private RootCore() {}

    public static RootCoreSession open(Context context) throws Exception {
        return open(context, 15_000L);
    }

    public static RootCoreSession open(Context context, long timeoutMs) throws Exception {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("RootCore.open() must not run on the main thread");
        }

        if (timeoutMs < 1_000L) {
            throw new IllegalArgumentException("Timeout must be at least 1000 ms");
        }

        return RootCoreSession.open(context.getApplicationContext(), timeoutMs);
    }
}
