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
import android.os.HandlerThread;
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
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;

public final class RootCoreSession implements AutoCloseable {

    private final Context context;
    private final java.lang.Process rootProcess;
    private final String sessionId;
    private final BroadcastReceiver receiver;
    private final HandlerThread receiverThread;
    private final IRootCoreManager remote;
    private final IBinder localToken;
    private final IBinder.DeathRecipient deathRecipient;
    private final AtomicBoolean binderAlive;

    private volatile boolean closed;
    private final Object stateLock = new Object();
    private final ExecutorService binderExecutor;

    private RootCoreSession(
            Context context,
            java.lang.Process rootProcess,
            String sessionId,
            BroadcastReceiver receiver,
            HandlerThread receiverThread,
            IRootCoreManager remote,
            IBinder localToken,
            IBinder.DeathRecipient deathRecipient,
            AtomicBoolean binderAlive
    ) {
        this.context = context;
        this.rootProcess = rootProcess;
        this.sessionId = sessionId;
        this.receiver = receiver;
        this.receiverThread = receiverThread;
        this.remote = remote;
        this.localToken = localToken;
        this.deathRecipient = deathRecipient;
        this.binderAlive = binderAlive;
        this.binderExecutor = new ThreadPoolExecutor(BINDER_EXECUTOR_THREADS, BINDER_EXECUTOR_THREADS, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(BINDER_EXECUTOR_QUEUE_CAPACITY),
                task -> {
                    Thread thread = new Thread(
                            task,
                            "RootCoreSession-" + sessionId
                    );
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy()
        );
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
        AtomicBoolean binderAlive = new AtomicBoolean(false);

        HandlerThread receiverThread = new HandlerThread(
                "RootCoreReceiver-" + sessionId
        );
        receiverThread.start();
        Handler receiverHandler = new Handler(receiverThread.getLooper());

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, Intent intent) {
                android.util.Log.i(
                        "RoutineBridgeRootCore",
                        "ROOTCORE_BROADCAST_RECEIVED session=" + sessionId.substring(0, 8)
                                + " thread=" + Thread.currentThread().getName()
                );

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

                    android.util.Log.i(
                            "RoutineBridgeRootCore",
                            "ROOTCORE_BROADCAST_ACCEPTED session="
                                    + sessionId.substring(0, 8)
                    );

                    IBinder.DeathRecipient deathRecipient = () -> {
                        binderAlive.set(false);
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

                    binderAlive.set(true);
                    binder.linkToDeath(deathRecipient, 0);

                    if (!binder.isBinderAlive()) {
                        throw new RemoteException(
                                "Root Core Binder died before connection completed"
                        );
                    }

                    accepted = true;
                } catch (Throwable broadcastError) {
                    binderAlive.set(false);
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
        java.lang.Process process = null;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(
                        receiver, filter, null, receiverHandler,
                        Context.RECEIVER_EXPORTED
                );
            } else {
                context.registerReceiver(receiver, filter, null, receiverHandler);
            }
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
                    context, process, sessionId, receiver, receiverThread,
                    manager, token, death, binderAlive
            );
        } catch (Throwable error) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Throwable ignored) {
            }
            if (process != null) terminateProcess(process);
            receiverThread.quitSafely();
            throw error;
        }
    }

    private static final int BINDER_EXECUTOR_THREADS = 4;
    private static final int BINDER_EXECUTOR_QUEUE_CAPACITY = 64;
    private static final long BINDER_CALL_TIMEOUT_MS = 10_000L;

    public int ping() throws RemoteException {
        return callRemote("ping", () -> remote.ping());
    }

    public String createObject(
            String className,
            String[] constructorTypes,
            Bundle arguments
    ) throws RemoteException {
        if (className == null || className.isBlank()) {
            throw new IllegalArgumentException("Class name is empty");
        }
        String objectId = callRemote(
                "createObject",
                () -> remote.createObject(
                        className,
                        constructorTypes == null ? new String[0] : constructorTypes,
                        arguments == null ? new Bundle() : arguments
                )
        );
        if (objectId == null || objectId.isBlank()) {
            throw new RemoteException("Root Core returned an empty object ID");
        }
        return objectId;
    }

    public Bundle invokeObject(
            String objectId,
            String methodName,
            String[] parameterTypes,
            Bundle arguments
    ) throws RemoteException {
        if (objectId == null || objectId.isBlank()
                || methodName == null || methodName.isBlank()) {
            throw new IllegalArgumentException("Object ID or method name is empty");
        }
        Bundle result = callRemote(
                "invokeObject",
                () -> remote.invokeObject(
                        objectId,
                        methodName,
                        parameterTypes == null ? new String[0] : parameterTypes,
                        arguments == null ? new Bundle() : arguments
                )
        );
        requireSuccessfulObjectOperation(result, "invokeObject");
        return result;
    }

    public void releaseObject(String objectId) throws RemoteException {
        if (objectId == null || objectId.isBlank()) {
            throw new IllegalArgumentException("Object ID is empty");
        }

        Bundle result = callRemote(
                "releaseObject",
                () -> remote.releaseObject(objectId)
        );
        requireSuccessfulObjectOperation(result, "releaseObject");
    }

    private static void requireSuccessfulObjectOperation(
            Bundle result,
            String operation
    ) throws RemoteException {
        if (result == null) {
            throw new RemoteException(
                    "Root Core returned a null result for " + operation
            );
        }

        String status = result.getString("status");
        if ("error".equals(status)) {
            String type = result.getString("error_type", "UnknownError");
            String message = result.getString(
                    "error_message",
                    "Root Core operation failed without a message"
            );
            throw new RemoteException(
                    "Root Core " + operation + " failed ["
                            + type + "]: " + message
            );
        }

        if (!"success".equals(status)) {
            throw new RemoteException(
                    "Root Core returned an invalid status for " + operation
            );
        }
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

        callRemote("launchActivity", 20_000L, () -> {
            remote.launchActivity(intent);
            return null;
        });
    }

    private static RemoteException remoteException(
            String message,
            Throwable cause
    ) {
        RemoteException exception = new RemoteException(message);
        exception.initCause(cause);
        return exception;
    }

    private <T> T callRemote(
            String operation,
            Callable<T> call
    ) throws RemoteException {
        return callRemote(operation, BINDER_CALL_TIMEOUT_MS, call);
    }

    private <T> T callRemote(
            String operation,
            long timeoutMs,
            Callable<T> call
    ) throws RemoteException {
        Future<T> future;
        synchronized (stateLock) {
            ensureOpen();
            try {
                future = binderExecutor.submit(() -> {
                String tag = "RoutineBridgeRootCore";
                String prefix = "CLIENT_BINDER_CALL ";
                android.util.Log.i(
                        tag,
                        prefix + "BEGIN operation=" + operation
                                + " session=" + sessionId.substring(0, 8)
                                + " thread=" + Thread.currentThread().getName()
                );
                try {
                    T result = call.call();
                    android.util.Log.i(
                            tag,
                            prefix + "RETURN operation=" + operation
                                    + " session=" + sessionId.substring(0, 8)
                                    + " resultNull=" + (result == null)
                    );
                    return result;
                } catch (Exception error) {
                    android.util.Log.e(
                            tag,
                            prefix + "THROW operation=" + operation
                                    + " session=" + sessionId.substring(0, 8)
                                    + " type=" + error.getClass().getName()
                                    + " message=" + error.getMessage(),
                            error
                    );
                    throw error;
                } catch (Error error) {
                    android.util.Log.e(
                            tag,
                            prefix + "ERROR operation=" + operation
                                    + " session=" + sessionId.substring(0, 8)
                                    + " type=" + error.getClass().getName(),
                            error
                    );
                    throw error;
                }
                });
            } catch (RejectedExecutionException error) {
                throw remoteException(
                        "Root Core executor is full or unavailable: " + operation,
                        error
                );
            }
        }

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException error) {
            future.cancel(true);
            throw new RemoteException(
                    "Root Core Binder call timed out after "
                            + timeoutMs + " ms: " + operation
            );
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw remoteException(
                    "Interrupted while waiting for Root Core Binder: " + operation,
                    error
            );
        } catch (CancellationException error) {
            throw remoteException(
                    "Root Core Binder call was cancelled: " + operation,
                    error
            );
        } catch (ExecutionException error) {
            Throwable cause = error.getCause();
            android.util.Log.e(
                    "RoutineBridgeRootCore",
                    "CLIENT_BINDER_EXECUTION_EXCEPTION operation=" + operation
                            + " session=" + sessionId.substring(0, 8)
                            + " causeType=" + (cause == null
                                    ? "null" : cause.getClass().getName())
                            + " causeMessage=" + (cause == null
                                    ? "null" : cause.getMessage()),
                    cause == null ? error : cause
            );
            if (cause instanceof RemoteException) {
                throw (RemoteException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            if (cause instanceof Error) {
                throw (Error) cause;
            }
            throw remoteException(
                    "Root Core Binder call failed: " + operation,
                    cause
            );
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Root Core session is closed");
        }
    }

    @Override
    public void close() {
        synchronized (stateLock) {
            if (closed) return;
            closed = true;
        }

        // AIDL close() is oneway: send it directly, even after a Binder timeout.
        // Do not queue it behind a possibly blocked synchronous Binder call.
        try {
            remote.close();
        } catch (Throwable ignored) {
        }
        for (Runnable pending : binderExecutor.shutdownNow()) {
            if (pending instanceof Future<?>) {
                ((Future<?>) pending).cancel(false);
            }
        }

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
        receiverThread.quitSafely();
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

    /**
     * Returns whether this session is open and its remote Binder and root process are alive.
     * This is a local state check; a subsequent Binder call can still fail if the process dies.
     */
    public boolean isConnected() {
        return !closed
                && binderAlive.get()
                && remote.asBinder().isBinderAlive()
                && rootProcess.isAlive();
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
