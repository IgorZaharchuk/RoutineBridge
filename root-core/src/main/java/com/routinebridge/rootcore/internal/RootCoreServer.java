package com.routinebridge.rootcore.internal;

import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;

import com.routinebridge.rootcore.ipc.IRootCoreManager;


public final class RootCoreServer extends IRootCoreManager.Stub {

    private static final String TAG = "RoutineBridgeRootCore";
    private static final long IDLE_TIMEOUT_MS = 15_000L;

    private final Context context;
    private final int clientUid;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private IBinder clientToken;
    private IBinder.DeathRecipient deathRecipient;
    private volatile boolean connected;
    private volatile boolean closed;

    private final Runnable idleShutdown = this::shutdown;

    public RootCoreServer(Context context, int clientUid) {
        this.context = context;
        this.clientUid = clientUid;
    }

    public void start() {
        if (Process.myUid() != 0) {
            throw new SecurityException("Root process has UID " + Process.myUid());
        }

        handler.postDelayed(idleShutdown, IDLE_TIMEOUT_MS);

        Intent intent = new Intent(context.getPackageName() + ".ROOTCORE_BIND");
        intent.setPackage(context.getPackageName());
        intent.addFlags(0x00400000); // FLAG_RECEIVER_FROM_SHELL
        Bundle binderBundle = new Bundle();
        binderBundle.putBinder("binder", asBinder());
        intent.putExtra("extra.bundle", binderBundle);

        context.sendBroadcastAsUser(
                intent,
                UserHandle.getUserHandleForUid(clientUid)
        );

        android.util.Log.i(TAG, "Binder broadcast sent; root UID=" + Process.myUid());
    }

    @Override
    public int ping() {
        enforceClient();
        return Process.myUid();
    }

    @Override
    public synchronized void connect(IBinder client) throws RemoteException {
        enforceClient();

        if (closed) {
            throw new RemoteException("Root Core session is already closed");
        }

        if (client == null || !client.isBinderAlive()) {
            throw new RemoteException("Client token is not alive");
        }

        if (connected) {
            if (clientToken == client) return;
            throw new RemoteException("A client is already connected");
        }

        clientToken = client;
        deathRecipient = this::onClientDied;
        client.linkToDeath(deathRecipient, 0);
        connected = true;
        handler.removeCallbacks(idleShutdown);

        android.util.Log.i(TAG, "Client connected");
    }

    @Override
    public Bundle query(String uri, String[] projection) throws RemoteException {
        enforceConnectedClient();
        return ExternalProviderQuery.query(uri, projection, clientUid);
    }

    @Override
    public void launchActivity(Intent intent) throws RemoteException {
        enforceConnectedClient();
        RootActivityLauncher.launch(intent, clientUid);
    }

    @Override
    public void close() {
        enforceClient();
        handler.post(this::shutdown);
    }

    private void enforceClient() {
        if (Binder.getCallingUid() != clientUid) {
            throw new SecurityException(
                    "Unexpected Binder caller UID: " + Binder.getCallingUid()
            );
        }
    }

    private void enforceConnectedClient() {
        enforceClient();
        if (!connected || closed) {
            throw new SecurityException("Root Core session is not connected");
        }
    }

    private void onClientDied() {
        handler.post(() -> {
            android.util.Log.w(TAG, "Client Binder died");
            shutdown();
        });
    }

    private synchronized void shutdown() {
        if (closed) return;
        closed = true;

        handler.removeCallbacks(idleShutdown);

        if (clientToken != null && deathRecipient != null) {
            try {
                clientToken.unlinkToDeath(deathRecipient, 0);
            } catch (Throwable ignored) {
            }
        }

        clientToken = null;
        deathRecipient = null;
        connected = false;

        android.util.Log.i(TAG, "Root Core session closing");
        handler.postDelayed(() -> System.exit(0), 150L);
    }
}
