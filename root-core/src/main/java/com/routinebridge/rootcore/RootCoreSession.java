package com.routinebridge.rootcore;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;

import com.routinebridge.rootcore.ipc.IRootCoreManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;

public final class RootCoreSession implements AutoCloseable {

    private final Context context;
    private final java.lang.Process rootProcess;
    private final String sessionId;
    private final BroadcastReceiver receiver;
    private final IRootCoreManager remote;
    private final IBinder localToken;
    private final IBinder.DeathRecipient deathRecipient;

    private volatile boolean closed;
    private volatile boolean unusable;
    private final ExecutorService binderExecutor;

    private RootCoreSession(
            Context context,
            java.lang.Process rootProcess,
            String sessionId,
            BroadcastReceiver receiver,
            IRootCoreManager remote,
            IBinder localToken,
            IBinder.DeathRecipient deathRecipient
    ) {
        this.context = context;
        this.rootProcess = rootProcess;
        this.sessionId = sessionId;
        this.receiver = receiver;
        this.remote = remote;
        this.localToken = localToken;
        this.deathRecipient = deathRecipient;
        this.binderExecutor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "RootCoreSession-" + sessionId);
            thread.setDaemon(true);
            return thread;
        });
    }

    static RootCoreSession open(
            Context context,
            long timeoutMs,
            RootCorePolicy policy
    ) throws Exception {
        if (policy == null) {
            throw new IllegalArgumentException("RootCore policy is null");
        }
        String encodedPolicy = policy.encode();
        File jar = installBootstrapAsset(context);

        int uid = Process.myUid();
        int userId = uid / 100000;
        String packageName = context.getPackageName();
        String sessionId = UUID.randomUUID().toString();
        String action = packageName + ".ROOTCORE_BIND." + sessionId;

        CountDownLatch connected = new CountDownLatch(1);
        AtomicReference<IRootCoreManager> remoteRef = new AtomicReference<>();
        AtomicReference<IBinder> tokenRef = new AtomicReference<>();
        AtomicReference<IBinder.DeathRecipient> deathRef = new AtomicReference<>();
        AtomicReference<Throwable> connectionError = new AtomicReference<>();

        Handler mainHandler = new Handler(Looper.getMainLooper());

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, Intent intent) {
                if (!action.equals(intent.getAction())) {
                    android.util.Log.w(
                            "RoutineBridgeRootCore",
                            "Ignoring Binder broadcast with unexpected action"
                    );
                    return;
                }

                if (Build.VERSION.SDK_INT >= 34) {
                    int senderUid = getSentFromUid();
                    if (senderUid != 0 && senderUid != Process.INVALID_UID) {
                        android.util.Log.w(
                                "RoutineBridgeRootCore",
                                "Ignoring Binder broadcast from UID " + senderUid
                        );
                        return;
                    }
                    if (senderUid == Process.INVALID_UID) {
                        android.util.Log.w(
                                "RoutineBridgeRootCore",
                                "Sender UID unavailable; verifying Binder identity"
                        );
                    }
                }

                if (!sessionId.equals(intent.getStringExtra("extra.session_id"))) {
                    android.util.Log.w(
                            "RoutineBridgeRootCore",
                            "Ignoring Binder broadcast for another session"
                    );
                    return;
                }
                boolean accepted = false;
                try {
                    Bundle extras = intent.getBundleExtra("extra.bundle");
                    IBinder binder = extras == null ? null : extras.getBinder("binder");
                    if (binder == null) {
                        throw new RemoteException("Root Core broadcast has no Binder");
                    }

                    IRootCoreManager manager =
                            IRootCoreManager.Stub.asInterface(binder);
                    if (manager == null) {
                        throw new RemoteException("Root Core Binder interface is unavailable");
                    }

                    int rootUid = manager.ping();
                    if (rootUid != 0) {
                        throw new SecurityException("Root Core Binder UID is " + rootUid);
                    }

                    IBinder token = new Binder();
                    manager.connect(token);

                    IBinder.DeathRecipient deathRecipient = () -> {
                        remoteRef.set(null);
                        connectionError.compareAndSet(
                                null,
                                new RemoteException("Root Core Binder died during connection")
                        );
                        connected.countDown();
                    };

                    remoteRef.set(manager);
                    tokenRef.set(token);
                    deathRef.set(deathRecipient);

                    binder.linkToDeath(deathRecipient, 0);

                    if (!binder.isBinderAlive()) {
                        throw new RemoteException(
                                "Root Core Binder died before connection completed"
                        );
                    }

                    accepted = true;
                } catch (Throwable broadcastError) {
                    remoteRef.set(null);
                    tokenRef.set(null);
                    deathRef.set(null);
                    connectionError.compareAndSet(null, broadcastError);
                    connected.countDown();

                    android.util.Log.e(
                            "RoutineBridgeRootCore",
                            "Failed to accept Root Core Binder broadcast",
                            broadcastError
                    );
                } finally {
                    if (accepted) {
                        connected.countDown();
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(action);
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter, null, mainHandler);
        }

        java.lang.Process process = null;
        try {
            String command =
                    "CLASSPATH=" + shellQuote(jar.getAbsolutePath()) +
                    " app_process /system/bin " +
                    "com.routinebridge.rootcore.bootstrap.RootServerMain " +
                    shellQuote(packageName) + " " +
                    uid + " " + userId + " " +
                    shellQuote(sessionId) + " " +
                    shellQuote(encodedPolicy) + " >/dev/null 2>&1";

            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .redirectOutput(new File("/dev/null"))
                    .start();
            process.getOutputStream().close();

            boolean arrived = connected.await(timeoutMs, TimeUnit.MILLISECONDS);

            if (!arrived) {
                Throwable cause = connectionError.get();
                if (cause != null) {
                    throw new IOException(
                            "Failed to accept Root Core Binder broadcast before timeout",
                            cause
                    );
                }
                throw new TimeoutException("Timed out waiting for Root Core Binder");
            }

            IRootCoreManager manager = remoteRef.get();
            IBinder token = tokenRef.get();
            IBinder.DeathRecipient death = deathRef.get();

            if (manager == null || token == null || death == null || !process.isAlive()) {
                Throwable cause = connectionError.get();
                if (cause != null) {
                    throw new IOException(
                            "Failed to establish Root Core Binder connection", cause
                    );
                }
                throw new IOException("Root Core process exited before session became ready");
            }

            return new RootCoreSession(
                    context, process, sessionId, receiver, manager, token, death
            );
        } catch (Throwable error) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Throwable ignored) {
            }
            if (process != null) terminateProcess(process);
            throw error;
        }
    }

    private static final long BINDER_CALL_TIMEOUT_MS = 10_000L;

    public int ping() throws RemoteException {
        return callRemote("ping", () -> remote.ping());
    }

    public Bundle queryProvider(Uri uri, String[] projection) throws RemoteException {
        if (uri == null) throw new IllegalArgumentException("URI is null");

        Bundle result = callRemote(
                "queryProvider",
                () -> remote.query(uri.toString(), projection)
        );
        if (result == null) {
            throw new RemoteException(
                    "Root Core server returned a null Bundle for Provider query"
            );
        }
        return result;
    }

    public void launchActivity(
            ComponentName component,
            String action,
            Bundle extras,
            int flags
    ) throws RemoteException {
        if (component == null) {
            throw new IllegalArgumentException("Explicit Activity component is required");
        }

        Intent intent = new Intent();
        if (action != null) intent.setAction(action);
        intent.setComponent(component);
        intent.addFlags(flags);
        if (extras != null) intent.putExtras(extras);

        callRemote("launchActivity", () -> {
            remote.launchActivity(intent);
            return null;
        });
    }

    private synchronized <T> T callRemote(
            String operation,
            Callable<T> call
    ) throws RemoteException {
        ensureOpen();

        Future<T> future;
        try {
            future = binderExecutor.submit(call);
        } catch (RejectedExecutionException error) {
            unusable = true;
            throw new RemoteException(
                    "Root Core executor is unavailable: " + operation,
                    error
            );
        }

        try {
            return future.get(BINDER_CALL_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            unusable = true;
            future.cancel(true);
            throw new RemoteException(
                    "Root Core Binder call timed out after "
                            + BINDER_CALL_TIMEOUT_MS + " ms: " + operation
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new RemoteException(
                    "Interrupted while waiting for Root Core Binder: " + operation,
                    error
            );
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            if (cause instanceof RemoteException) {
                throw (RemoteException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw new RemoteException(
                    "Root Core Binder call failed: " + operation,
                    cause
            );
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Root Core session is closed");
        }
        if (unusable) {
            throw new IllegalStateException(
                    "Root Core session is unusable after a Binder timeout"
            );
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;

        if (!unusable) {
            try {
                binderExecutor.execute(() -> {
                    try {
                        remote.close();
                    } catch (Throwable ignored) {
                    }
                });
            } catch (RejectedExecutionException ignored) {
            }
        }
        binderExecutor.shutdown();

        try {
            if (deathRecipient != null) {
                remote.asBinder().unlinkToDeath(deathRecipient, 0);
            }
        } catch (Throwable ignored) {
        }

        try {
            context.unregisterReceiver(receiver);
        } catch (Throwable ignored) {
        }
    }

    public boolean awaitTermination(long timeoutMs) throws InterruptedException {
        if (timeoutMs < 0) {
            throw new IllegalArgumentException("Timeout cannot be negative");
        }
        return rootProcess.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
    }

    public boolean isRootProcessAlive() {
        return rootProcess.isAlive();
    }

    public String getSessionId() {
        return sessionId;
    }

    private static void terminateProcess(java.lang.Process process) {
        process.destroy();
        try {
            if (!process.waitFor(1, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(1, TimeUnit.SECONDS);
            }
        } catch (InterruptedException error) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static synchronized File installBootstrapAsset(Context context)
            throws IOException {
        File directory = new File(context.getFilesDir(), "rootcore");
        if (!directory.isDirectory() && !directory.mkdirs()
                && !directory.isDirectory()) {
            throw new IOException("Cannot create Root Core directory");
        }

        File target = new File(directory, "main.jar");

        byte[] assetHash;
        try (InputStream input = context.getAssets().open("main.jar")) {
            assetHash = sha256(input);
        }

        if (target.isFile()) {
            try (InputStream input = new java.io.FileInputStream(target)) {
                if (MessageDigest.isEqual(assetHash, sha256(input))) {
                    return target;
                }
            }
        }

        if (target.exists() && !target.delete()) {
            throw new IOException("Cannot replace outdated Root Core bootstrap asset");
        }

        File temporary = File.createTempFile("main-", ".jar.tmp", directory);

        try {
            try (InputStream input = context.getAssets().open("main.jar");
                 FileOutputStream output = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                output.getFD().sync();
            }

            if (!temporary.renameTo(target)) {
                throw new IOException("Cannot install Root Core bootstrap asset");
            }

            return target;
        } finally {
            if (temporary.exists()) {
                temporary.delete();
            }
        }
    }

    private static byte[] sha256(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int count;

            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }

            return digest.digest();
        } catch (NoSuchAlgorithmException error) {
            throw new AssertionError("SHA-256 is unavailable", error);
        }
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
