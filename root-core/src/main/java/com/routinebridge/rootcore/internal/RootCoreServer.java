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

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import com.routinebridge.rootcore.RootCorePolicy;
import com.routinebridge.rootcore.ipc.IRootCoreManager;


public final class RootCoreServer extends IRootCoreManager.Stub {

    private static final String TAG = "RoutineBridgeRootCore";
    private static final long IDLE_TIMEOUT_MS = 15_000L;
    private static final long RELEASE_CLOSE_TIMEOUT_MS = 8_000L;
    private static final long SHUTDOWN_GRACE_PERIOD_MS = 2_000L;

    private final Context context;
    private final int clientUid;
    private final String sessionId;
    private final RootCorePolicy policy;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ObjectRegistry objects = new ObjectRegistry();

    private IBinder clientToken;
    private IBinder.DeathRecipient deathRecipient;
    private volatile boolean connected;
    private volatile boolean closed;

    private final Runnable idleShutdown = this::shutdown;

    public RootCoreServer(
            Context context,
            int clientUid,
            String sessionId,
            String encodedPolicy
    ) {
        if (sessionId == null || sessionId.isEmpty()) {
            throw new IllegalArgumentException("Session ID is empty");
        }
        this.context = context;
        this.clientUid = clientUid;
        this.sessionId = sessionId;
        this.policy = RootCorePolicy.decode(encodedPolicy);
    }

    public void start() {
        if (Process.myUid() != 0) {
            throw new SecurityException("Root process has UID " + Process.myUid());
        }

        handler.postDelayed(idleShutdown, IDLE_TIMEOUT_MS);

        Intent intent = new Intent(
                context.getPackageName() + ".ROOTCORE_BIND." + sessionId
        );
        intent.setPackage(context.getPackageName());
        intent.putExtra("extra.session_id", sessionId);
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
        return ExternalProviderQuery.query(
                uri, projection, clientUid, policy
        );
    }

    @Override
    public void launchActivity(Intent intent) throws RemoteException {
        enforceConnectedClient();
        RootActivityLauncher.launch(intent, clientUid, policy);
    }

    private void enforceClientCodePermission() {
        enforceConnectedClient();
        if (!policy.allowsClientCodeExecution()) {
            throw new SecurityException(
                    "Client code execution is not allowed by RootCorePolicy"
            );
        }
        if (Process.myUid() != 0) {
            throw new SecurityException("Client code execution requires UID 0");
        }
    }

    private static Class<?> resolveType(String name) throws ClassNotFoundException {
        if (name == null) throw new IllegalArgumentException("Type name is null");
        switch (name) {
            case "boolean": return boolean.class;
            case "int": return int.class;
            case "long": return long.class;
            case "float": return float.class;
            case "double": return double.class;
            case "java.lang.String": return String.class;
            case "android.os.Bundle": return Bundle.class;
            default:
                throw new IllegalArgumentException("Unsupported parameter type: " + name);
        }
    }

    private static Object readArgument(Bundle args, int index, Class<?> type) {
        String key = "arg" + index;
        if (!args.containsKey(key)) {
            throw new IllegalArgumentException("Missing argument: " + key);
        }
        if (type == boolean.class) return args.getBoolean(key);
        if (type == int.class) return args.getInt(key);
        if (type == long.class) return args.getLong(key);
        if (type == float.class) return args.getFloat(key);
        if (type == double.class) return args.getDouble(key);
        if (type == String.class) return args.getString(key);
        if (type == Bundle.class) return args.getBundle(key);
        throw new IllegalArgumentException("Unsupported argument type: " + type);
    }

    private static Object[] decodeArguments(String[] names, Bundle args)
            throws ClassNotFoundException {
        if (names == null) names = new String[0];
        if (args == null) args = new Bundle();
        Object[] values = new Object[names.length];
        for (int i = 0; i < names.length; i++) {
            values[i] = readArgument(args, i, resolveType(names[i]));
        }
        return values;
    }

    private static Bundle encodeResult(Object value, Class<?> returnType) {
        Bundle result = new Bundle();
        result.putString("return_type", returnType.getName());
        if (returnType == void.class) {
            result.putString("value_kind", "void");
        } else if (returnType == boolean.class || returnType == Boolean.class) {
            result.putString("value_kind", "boolean");
            result.putBoolean("value_boolean", (Boolean) value);
        } else if (returnType == int.class || returnType == Integer.class) {
            result.putString("value_kind", "int");
            result.putInt("value_int", (Integer) value);
        } else if (returnType == long.class || returnType == Long.class) {
            result.putString("value_kind", "long");
            result.putLong("value_long", (Long) value);
        } else if (returnType == float.class || returnType == Float.class) {
            result.putString("value_kind", "float");
            result.putFloat("value_float", (Float) value);
        } else if (returnType == double.class || returnType == Double.class) {
            result.putString("value_kind", "double");
            result.putDouble("value_double", (Double) value);
        } else if (returnType == String.class) {
            result.putString("value_kind", "string");
            result.putString("value_string", (String) value);
        } else if (returnType == Bundle.class) {
            result.putString("value_kind", "bundle");
            result.putBundle("value_bundle", (Bundle) value);
        } else {
            throw new IllegalArgumentException(
                    "Unsupported return type: " + returnType.getName()
            );
        }
        return result;
    }

    @Override
    public String createObject(
            String className,
            String[] constructorTypes,
            Bundle arguments
    ) throws RemoteException {
        enforceClientCodePermission();

        Object instance = null;
        try {
            if (className == null || !className.matches("[A-Za-z_$][A-Za-z0-9_$.]*")) {
                throw new IllegalArgumentException("Invalid client class name");
            }

            Class<?> type = context.getClassLoader().loadClass(className);
            Class<?>[] parameterTypes = new Class<?>[
                    constructorTypes == null ? 0 : constructorTypes.length
            ];

            for (int i = 0; i < parameterTypes.length; i++) {
                parameterTypes[i] = resolveType(constructorTypes[i]);
            }

            Constructor<?> constructor = type.getConstructor(parameterTypes);
            Object[] values = decodeArguments(constructorTypes, arguments);
            instance = constructor.newInstance(values);

            String id = UUID.randomUUID().toString();
            ObjectEntry entry = new ObjectEntry(instance);

            if (!objects.register(id, entry)) {
                entry.requestClose();
                throw new IllegalStateException(
                        "Root Core session is closing; object was not registered"
                );
            }

            return id;
        } catch (Throwable error) {
            throw asRemoteException("Create client object failed", error);
        }
    }

    @Override
    public Bundle invokeObject(
            String objectId,
            String methodName,
            String[] parameterTypes,
            Bundle arguments
    ) throws RemoteException {
        enforceClientCodePermission();

        android.util.Log.i(TAG,
                "OBJECT_INVOKE_BEGIN session=" + sessionId.substring(0, 8)
                        + " pid=" + Process.myPid()
                        + " id=" + shortObjectId(objectId)
                        + " exists=" + objects.containsKey(objectId));

        try {
            ObjectEntry entry = objects.get(objectId);
            if (entry == null) {
                throw new IllegalArgumentException("Unknown or released object ID");
            }

            if (methodName == null || !methodName.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                throw new IllegalArgumentException("Invalid method name");
            }

            Class<?>[] types = new Class<?>[
                    parameterTypes == null ? 0 : parameterTypes.length
            ];

            for (int i = 0; i < types.length; i++) {
                types[i] = resolveType(parameterTypes[i]);
            }

            Method method = entry.instance().getClass().getMethod(methodName, types);
            if (method.getDeclaringClass() == Object.class) {
                throw new SecurityException("Object base methods are not callable");
            }

            Object[] values = decodeArguments(parameterTypes, arguments);
            Object value = entry.invoke(method, values);

            Bundle result = encodeResult(value, method.getReturnType());
            result.putString("status", "success");

            android.util.Log.i(TAG,
                    "OBJECT_INVOKE_SUCCESS session=" + sessionId.substring(0, 8)
                            + " id=" + shortObjectId(objectId));
            return result;
        } catch (Throwable error) {
            android.util.Log.e(TAG,
                    "OBJECT_INVOKE_ERROR session=" + sessionId.substring(0, 8)
                            + " id=" + shortObjectId(objectId),
                    error);
            return operationErrorResult("Invoke client object failed", error);
        }
    }

    @Override
    public Bundle releaseObject(String objectId) throws RemoteException {
        enforceClientCodePermission();

        try {
            ObjectEntry removed = objects.remove(objectId);

            android.util.Log.i(TAG,
                    "OBJECT_RELEASE session=" + sessionId.substring(0, 8)
                            + " id=" + shortObjectId(objectId)
                            + " removed=" + (removed != null)
                            + " size=" + objects.size());

            if (removed == null) {
                throw new IllegalArgumentException(
                        "Unknown or already released object ID"
                );
            }

            Throwable closeError = removed.closeAndWait(
                    RELEASE_CLOSE_TIMEOUT_MS
            );

            if (closeError != null) {
                throw new IllegalStateException(
                        "Object was released, but close failed",
                        closeError
                );
            }

            Bundle result = new Bundle();
            result.putString("status", "success");
            result.putBoolean("released", true);
            return result;
        } catch (Throwable error) {
            android.util.Log.e(TAG,
                    "OBJECT_RELEASE_ERROR session=" + sessionId.substring(0, 8)
                            + " id=" + shortObjectId(objectId),
                    error);
            return operationErrorResult("Release client object failed", error);
        }
    }

    private static Bundle operationErrorResult(String operation, Throwable error) {
        Throwable cause = error;
        if (cause instanceof InvocationTargetException
                && ((InvocationTargetException) cause).getCause() != null) {
            cause = ((InvocationTargetException) cause).getCause();
        }

        Bundle result = new Bundle();
        result.putString("status", "error");
        result.putString("error_type", cause.getClass().getName());
        result.putString(
                "error_message",
                operation + ": " + cause.getClass().getSimpleName()
                        + ": " + String.valueOf(cause.getMessage())
        );
        return result;
    }

    private static String shortObjectId(String objectId) {
        if (objectId == null) return "null";
        return objectId.substring(0, Math.min(8, objectId.length()));
    }

    private static RemoteException asRemoteException(String message, Throwable error) {
        Throwable cause = error;
        if (cause instanceof InvocationTargetException
                && ((InvocationTargetException) cause).getCause() != null) {
            cause = ((InvocationTargetException) cause).getCause();
        }
        RemoteException result = new RemoteException(
                message + ": " + cause.getClass().getSimpleName()
                        + ": " + String.valueOf(cause.getMessage())
        );
        result.initCause(cause);
        return result;
    }

    private static final class ObjectRegistry {
        private final Map<String, ObjectEntry> entries = new HashMap<>();
        private boolean closed;

        synchronized boolean register(String id, ObjectEntry entry) {
            if (closed) return false;
            entries.put(id, entry);
            return true;
        }

        synchronized ObjectEntry get(String id) {
            return closed || id == null ? null : entries.get(id);
        }

        synchronized ObjectEntry remove(String id) {
            if (closed || id == null) return null;

            ObjectEntry entry = entries.remove(id);
            if (entry != null) {
                entry.markClosing();
            }
            return entry;
        }

        synchronized boolean containsKey(String id) {
            return !closed && id != null && entries.containsKey(id);
        }

        synchronized int size() {
            return entries.size();
        }

        synchronized List<ObjectEntry> detachAll() {
            closed = true;
            List<ObjectEntry> result = new ArrayList<>(entries.values());

            for (ObjectEntry entry : result) {
                entry.markClosing();
            }

            entries.clear();
            return result;
        }
    }

    private static final class ObjectEntry {
        private final Object instance;

        private boolean closing;
        private boolean closeStarted;
        private boolean closeFinished;
        private int activeInvocations;
        private Throwable closeError;

        ObjectEntry(Object instance) {
            this.instance = instance;
        }

        Object instance() {
            return instance;
        }

        Object invoke(Method method, Object[] arguments) throws Throwable {
            beginInvocation();
            try {
                return method.invoke(instance, arguments);
            } finally {
                endInvocation();
            }
        }

        private synchronized void beginInvocation() {
            if (closing) {
                throw new IllegalStateException("Object is closing or has been released");
            }

            activeInvocations++;
        }

        private synchronized void endInvocation() {
            if (activeInvocations > 0) {
                activeInvocations--;
            }
            notifyAll();
        }

        synchronized void markClosing() {
            closing = true;
            notifyAll();
        }

        void requestClose() {
            Thread worker = null;

            synchronized (this) {
                closing = true;
                notifyAll();

                if (closeStarted || closeFinished) return;
                closeStarted = true;

                if (!(instance instanceof AutoCloseable)) {
                    closeFinished = true;
                    notifyAll();
                    return;
                }

                worker = new Thread(this::performClose, "RootCoreObjectClose");
                worker.setDaemon(true);
            }

            try {
                worker.start();
            } catch (Throwable error) {
                synchronized (this) {
                    closeError = error;
                    closeFinished = true;
                    notifyAll();
                }
            }
        }

        private void performClose() {
            boolean interrupted = false;

            try {
                synchronized (this) {
                    while (activeInvocations > 0) {
                        try {
                            wait();
                        } catch (InterruptedException ignored) {
                            interrupted = true;
                        }
                    }
                }

                ((AutoCloseable) instance).close();
            } catch (Exception error) {
                closeError = error;
            } catch (Error error) {
                closeError = error;
                throw error;
            } finally {
                synchronized (this) {
                    closeFinished = true;
                    notifyAll();
                }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        Throwable closeAndWait(long timeoutMs) {
            requestClose();
            long deadline = System.nanoTime() + timeoutMs * 1_000_000L;

            synchronized (this) {
                while (!closeFinished) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0L) {
                        return new TimeoutException(
                                "Timed out waiting for AutoCloseable.close()"
                        );
                    }

                    try {
                        long waitMs = Math.max(
                                1L,
                                Math.min(remaining / 1_000_000L, 1_000L)
                        );
                        wait(waitMs);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        return error;
                    }
                }

                return closeError;
            }
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
            shutdown();
        });
    }

    private synchronized void shutdown() {
        if (closed) return;
        closed = true;

        handler.removeCallbacks(idleShutdown);

        List<ObjectEntry> remainingObjects = objects.detachAll();
        for (ObjectEntry entry : remainingObjects) {
            entry.requestClose();
        }

        if (clientToken != null && deathRecipient != null) {
            try {
                clientToken.unlinkToDeath(deathRecipient, 0);
            } catch (Throwable ignored) {
            }
        }

        clientToken = null;
        deathRecipient = null;
        connected = false;

        android.util.Log.i(TAG,
                "Root Core session closing; detached objects="
                        + remainingObjects.size());

        handler.postDelayed(
                () -> System.exit(0),
                SHUTDOWN_GRACE_PERIOD_MS
        );
    }
}
