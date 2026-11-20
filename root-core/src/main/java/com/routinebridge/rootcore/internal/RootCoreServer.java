package com.routinebridge.rootcore.internal;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Process;
import android.os.RemoteException;
import android.os.UserHandle;

import com.routinebridge.rootcore.ipc.IRootCoreManager;

import java.util.ArrayList;

public final class RootCoreServer extends IRootCoreManager.Stub {

    private static final String TAG = "RoutineBridgeRootCore";
    private static final long IDLE_TIMEOUT_MS = 15_000L;

    private final Context context;
    private final int clientUid;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private IBinder clientToken;
    private IBinder.DeathRecipient deathRecipient;
    private boolean connected;
    private boolean closed;

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

        if (uri == null || uri.trim().isEmpty()) {
            throw new RemoteException("Provider URI is empty");
        }

        Cursor cursor = null;
        android.util.Log.i(TAG, "Provider query started: uri=" + uri
                + ", projection=" + (projection == null ? "<all>" : projection.length));
        try {
            cursor = context.getContentResolver().query(
                    Uri.parse(uri),
                    projection,
                    null,
                    null,
                    null
            );

            if (cursor == null) {
                android.util.Log.e(TAG, "Provider returned a null Cursor: " + uri);
                throw new RemoteException("ContentProvider returned a null Cursor");
            }

            String[] columns = cursor.getColumnNames();
            android.util.Log.i(TAG, "Provider cursor received: columns=" + columns.length);

            Bundle result = new Bundle();
            result.putStringArray("columns", columns);

            ArrayList<Bundle> rows = new ArrayList<>();
            while (cursor.moveToNext()) {
                Bundle row = new Bundle();
                for (int i = 0; i < columns.length; i++) {
                    row.putString("c" + i, cursor.isNull(i) ? null : cursor.getString(i));
                }
                rows.add(row);
            }

            result.putParcelableArrayList("rows", rows);
            android.util.Log.i(TAG, "Provider query completed: rows=" + rows.size()
                    + ", columns=" + columns.length);
            return result;
        } catch (RemoteException error) {
            android.util.Log.e(TAG, "Provider query failed: " + uri, error);
            throw error;
        } catch (Throwable error) {
            android.util.Log.e(TAG, "Provider query crashed: " + uri, error);
            RemoteException remoteError = new RemoteException(
                    "Provider query failed: " + error.getClass().getName()
                            + ": " + error.getMessage()
            );
            remoteError.initCause(error);
            throw remoteError;
        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable closeError) {
                    android.util.Log.w(TAG, "Failed to close Provider cursor", closeError);
                }
            }
        }
    }

    @Override
    public void launchActivity(Intent intent) throws RemoteException {
        enforceConnectedClient();

        if (intent == null || intent.getComponent() == null) {
            throw new RemoteException("Only explicit Activity components are accepted");
        }

        try {
            Intent launch = new Intent(intent);
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(launch);
        } catch (Throwable error) {
            throw new RemoteException("Activity launch failed: " + error);
        }
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
            connected = false;
            clientToken = null;
            deathRecipient = null;
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
