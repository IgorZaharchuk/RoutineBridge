package com.routinebridge;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class RootCoreAutoCloseableProbe implements AutoCloseable {
    private String markerPath;
    private long closeDelayMs;
    private boolean failClose;
    private String invocationMarkerPath;
    private long invocationDelayMs;

    public RootCoreAutoCloseableProbe() {
    }

    public void configureClose(String markerPath, long closeDelayMs, boolean failClose) {
        this.markerPath = markerPath;
        this.closeDelayMs = closeDelayMs;
        this.failClose = failClose;
    }

    public void configureInvocation(String markerPath, long delayMs) {
        this.invocationMarkerPath = markerPath;
        this.invocationDelayMs = delayMs;
    }

    public String runInvocation() throws Exception {
        appendTo(invocationMarkerPath, "started\\n");
        Thread.sleep(invocationDelayMs);
        appendTo(invocationMarkerPath, "finished\\n");
        return "completed";
    }

    public boolean probe() {
        return true;
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
        appendTo(markerPath, value);
    }

    private void appendTo(String path, String value) throws IOException {
        if (path == null || path.isEmpty()) {
            throw new IOException("Marker path was not configured");
        }

        try (FileOutputStream output = new FileOutputStream(path, true)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
            output.getFD().sync();
        }
    }
}
