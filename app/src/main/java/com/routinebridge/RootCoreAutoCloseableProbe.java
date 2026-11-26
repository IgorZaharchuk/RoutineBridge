package com.routinebridge;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class RootCoreAutoCloseableProbe implements AutoCloseable {
    private String markerPath;
    private long closeDelayMs;
    private boolean failClose;

    public RootCoreAutoCloseableProbe() {
    }

    public void configureClose(
            String markerPath,
            long closeDelayMs,
            boolean failClose
    ) {
        this.markerPath = markerPath;
        this.closeDelayMs = closeDelayMs;
        this.failClose = failClose;
    }

    @Override
    public void close() throws Exception {
        append("started\n");

        if (closeDelayMs > 0L) {
            Thread.sleep(closeDelayMs);
        }

        if (failClose) {
            throw new IOException("Intentional diagnostic close failure");
        }

        append("closed\n");
    }

    private void append(String value) throws IOException {
        if (markerPath == null || markerPath.isEmpty()) {
            throw new IOException("Close marker path was not configured");
        }

        try (FileOutputStream output = new FileOutputStream(markerPath, true)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }
}
