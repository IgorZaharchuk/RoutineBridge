package com.routinebridge.rootcore.internal;

import android.content.Intent;
import android.os.Bundle;
import android.os.RemoteException;

import java.util.ArrayList;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class RootActivityLauncher {
    private static final String TAG = "RoutineBridgeRootCore";

    private RootActivityLauncher() {}

    static void launch(Intent intent, int clientUid) throws RemoteException {

        if (intent == null || intent.getComponent() == null) {
            throw new RemoteException(
                    "Only explicit Activity components are accepted"
            );
        }

        ArrayList<String> command = new ArrayList<>();
        command.add("/system/bin/am");
        command.add("start");
        command.add("--user");
        command.add(String.valueOf(clientUid / 100000));
        command.add("-n");
        command.add(intent.getComponent().flattenToShortString());

        if (intent.getAction() != null) {
            command.add("-a");
            command.add(intent.getAction());
        }

        command.add("-f");
        command.add(String.valueOf(intent.getFlags()));

        Bundle extras = intent.getExtras();
        if (extras != null) {
            for (String key : extras.keySet()) {
                if (key == null || key.isEmpty()) {
                    throw new RemoteException("Activity extra has an empty key");
                }

                Object value = extras.get(key);

                if (value instanceof String) {
                    command.add("--es");
                    command.add(key);
                    command.add((String) value);
                } else if (value instanceof Boolean) {
                    command.add("--ez");
                    command.add(key);
                    command.add(String.valueOf(value));
                } else if (value instanceof Integer) {
                    command.add("--ei");
                    command.add(key);
                    command.add(String.valueOf(value));
                } else if (value instanceof Long) {
                    command.add("--el");
                    command.add(key);
                    command.add(String.valueOf(value));
                } else if (value instanceof Float) {
                    command.add("--ef");
                    command.add(key);
                    command.add(String.valueOf(value));
                } else {
                    throw new RemoteException(
                            "Unsupported Activity extra type for key "
                                    + key + ": "
                                    + (value == null
                                        ? "null"
                                        : value.getClass().getName())
                    );
                }
            }
        }

        java.lang.Process process = null;

        try {
            android.util.Log.i(TAG,
                    "Starting Activity via am: "
                            + intent.getComponent().flattenToShortString());

            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();

            final java.lang.Process startedProcess = process;
            final ByteArrayOutputStream outputBuffer = new ByteArrayOutputStream();
            final java.util.concurrent.atomic.AtomicReference<Throwable> outputReadError =
                    new java.util.concurrent.atomic.AtomicReference<>();

            Thread outputReader = new Thread(() -> {
                try (InputStream input = startedProcess.getInputStream()) {
                    byte[] buffer = new byte[1024];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        outputBuffer.write(buffer, 0, count);
                    }
                } catch (Throwable error) {
                    outputReadError.set(error);
                }
            }, "RoutineBridge-am-output");
            outputReader.setDaemon(true);
            outputReader.start();

            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);

                try {
                    process.getInputStream().close();
                } catch (Throwable ignored) {
                }

                outputReader.join(1, TimeUnit.SECONDS);
                throw new RemoteException("am start timed out after 15 seconds");
            }

            outputReader.join();

            Throwable readError = outputReadError.get();
            if (readError != null) {
                RemoteException remoteError = new RemoteException(
                        "Failed to read am start output: "
                                + readError.getClass().getName()
                                + ": " + readError.getMessage()
                );
                remoteError.initCause(readError);
                throw remoteError;
            }

            int exitCode = process.exitValue();

            String output = new String(
                    outputBuffer.toByteArray(),
                    StandardCharsets.UTF_8
            ).trim();

            android.util.Log.i(TAG,
                    "am start finished: exitCode=" + exitCode
                            + ", output=" + output);

            if (exitCode != 0) {
                throw new RemoteException(
                        "am start failed, exitCode=" + exitCode
                                + ": " + output
                );
            }

        } catch (RemoteException error) {
            throw error;
        } catch (Throwable error) {
            throw new RemoteException(
                    "Failed to execute am start: "
                            + error.getClass().getName()
                            + ": " + error.getMessage()
            );
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

}
