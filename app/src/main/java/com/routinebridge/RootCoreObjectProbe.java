package com.routinebridge;

import android.os.Bundle;
import android.os.Process;

import java.io.FileInputStream;
import java.io.IOException;

public final class RootCoreObjectProbe {
    private int invocationCount;

    public RootCoreObjectProbe() {
    }

    public Bundle inspectFile(String path) {
        invocationCount++;

        Bundle result = new Bundle();
        result.putInt("uid", Process.myUid());
        result.putInt("pid", Process.myPid());
        result.putInt("invocation_count", invocationCount);
        result.putInt("bytes_read", 0);

        byte[] buffer = new byte[128];
        try (FileInputStream input = new FileInputStream(path)) {
            int count = input.read(buffer);
            result.putInt("bytes_read", Math.max(count, 0));
            if (count > 0) {
                result.putString(
                        "sample",
                        new String(buffer, 0, count, java.nio.charset.StandardCharsets.UTF_8)
                );
            }
        } catch (IOException | SecurityException error) {
            result.putString(
                    "read_error",
                    error.getClass().getSimpleName() + ": " + error.getMessage()
            );
        }

        return result;
    }

    public int getInvocationCount() {
        return invocationCount;
    }

    public void blockForDiagnostics(String markerPath) throws Exception {
        try (java.io.FileOutputStream marker =
                     new java.io.FileOutputStream(markerPath)) {
            marker.write("started".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            marker.getFD().sync();
        }
        Thread.sleep(30_000L);
    }
}
