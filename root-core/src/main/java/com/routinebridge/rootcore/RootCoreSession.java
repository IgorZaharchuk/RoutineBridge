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
import android.os.UserHandle;

import com.routinebridge.rootcore.ipc.IRootCoreManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public final class RootCoreSession implements AutoCloseable {

    private final Context context;
    private final java.lang.Process rootProcess;
    private final BroadcastReceiver receiver;
    private final IRootCoreManager remote;
    private final IBinder localToken;
    private final IBinder.DeathRecipient deathRecipient;

    private volatile boolean closed;

    private RootCoreSession(
            Context context,
            java.lang.Process rootProcess,
            BroadcastReceiver receiver,
            IRootCoreManager remote,
            IBinder localToken,
            IBinder.DeathRecipient deathRecipient
    ) {
        this.context = context;
        this.rootProcess = rootProcess;
        this.receiver = receiver;
        this.remote = remote;
        this.localToken = localToken;
        this.deathRecipient = deathRecipient;
    }

    static RootCoreSession open(Context context, long timeoutMs) throws Exception {
        File jar = installBootstrapAsset(context);

        int uid = Process.myUid();
        int userId = uid / 100000;
        String packageName = context.getPackageName();
        String action = packageName + ".ROOTCORE_BIND";

        CountDownLatch connected = new CountDownLatch(1);
        AtomicReference<IRootCoreManager> remoteRef = new AtomicReference<>();
        AtomicReference<IBinder> tokenRef = new AtomicReference<>();
        AtomicReference<IBinder.DeathRecipient> deathRef = new AtomicReference<>();
        AtomicReference<Throwable> receiverError = new AtomicReference<>();

        Handler mainHandler = new Handler(Looper.getMainLooper());

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ignored, Intent intent) {
                try {
                    Bundle extras = intent.getBundleExtra("extra.bundle");
                    IBinder binder = extras == null ? null : extras.getBinder("binder");
                    if (binder == null) {
                        throw new RemoteException("Root Core broadcast has no Binder");
                    }

                    IRootCoreManager manager =
                            IRootCoreManager.Stub.asInterface(binder);

                    int rootUid = manager.ping();
                    if (rootUid != 0) {
                        throw new SecurityException("Root Core Binder UID is " + rootUid);
                    }

                    IBinder token = new Binder();
                    manager.connect(token);

                    IBinder.DeathRecipient deathRecipient = () -> {
                        remoteRef.set(null);
                        connected.countDown();
                    };

                    binder.linkToDeath(deathRecipient, 0);

                    remoteRef.set(manager);
                    tokenRef.set(token);
                    deathRef.set(deathRecipient);
                } catch (Throwable error) {
                    receiverError.set(error);
                } finally {
                    connected.countDown();
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
                    uid + " " + userId + " >/dev/null 2>&1";

            process = new ProcessBuilder("su", "-c", command)
                    .redirectErrorStream(true)
                    .start();

            boolean arrived = connected.await(timeoutMs, TimeUnit.MILLISECONDS);

            if (!arrived) {
                throw new TimeoutException("Timed out waiting for Root Core Binder");
            }

            Throwable error = receiverError.get();
            if (error != null) {
                throw new IOException("Root Core Binder handshake failed", error);
            }

            IRootCoreManager manager = remoteRef.get();
            IBinder token = tokenRef.get();
            IBinder.DeathRecipient death = deathRef.get();

            if (manager == null || token == null || !process.isAlive()) {
                throw new IOException("Root Core process exited before session became ready");
            }

            return new RootCoreSession(
                    context, process, receiver, manager, token, death
            );
        } catch (Throwable error) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Throwable ignored) {
            }
            if (process != null) process.destroy();
            throw error;
        }
    }

    public int ping() throws RemoteException {
        ensureOpen();
        return remote.ping();
    }

    public Bundle queryProvider(Uri uri, String[] projection) throws RemoteException {
        ensureOpen();
        if (uri == null) throw new IllegalArgumentException("URI is null");
        return remote.query(uri.toString(), projection);
    }

    public void launchActivity(
            ComponentName component,
            String action,
            Bundle extras,
            int flags
    ) throws RemoteException {
        ensureOpen();

        if (component == null) {
            throw new IllegalArgumentException("Explicit Activity component is required");
        }

        Intent intent = new Intent();
        if (action != null) intent.setAction(action);
        intent.setComponent(component);
        intent.addFlags(flags);
        if (extras != null) intent.putExtras(extras);

        remote.launchActivity(intent);
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Root Core session is closed");
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;

        try {
            remote.close();
        } catch (Throwable ignored) {
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
    }

    private static File installBootstrapAsset(Context context) throws IOException {
        File directory = new File(context.getFilesDir(), "rootcore");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Cannot create Root Core directory");
        }

        File target = new File(directory, "main.jar");
        File temporary = new File(directory, "main.jar.tmp");

        try (java.io.InputStream input = context.getAssets().open("main.jar");
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        }

        if (!temporary.renameTo(target)) {
            temporary.delete();
            throw new IOException("Cannot install Root Core bootstrap asset");
        }

        return target;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
