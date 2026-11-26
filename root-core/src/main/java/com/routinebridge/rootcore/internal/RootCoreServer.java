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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.routinebridge.rootcore.RootCorePolicy;
import com.routinebridge.rootcore.ipc.IRootCoreManager;


public final class RootCoreServer extends IRootCoreManager.Stub {

    private static final String TAG = "RoutineBridgeRootCore";
    private static final long IDLE_TIMEOUT_MS = 15_000L;

    private final Context context;
    private final int clientUid;
    private final String sessionId;
    private final RootCorePolicy policy;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ConcurrentMap<String, Object> objects =
            new ConcurrentHashMap<>();

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
            Object instance = constructor.newInstance(values);
            String id = UUID.randomUUID().toString();
            objects.put(id, instance);
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
            Object instance = objects.get(objectId);
            if (instance == null) {
                android.util.Log.w(TAG,
                        "OBJECT_INVOKE_MISSING session=" + sessionId.substring(0, 8)
                                + " id=" + shortObjectId(objectId));
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
            Method method = instance.getClass().getMethod(methodName, types);
            if (method.getDeclaringClass() == Object.class) {
                throw new SecurityException("Object base methods are not callable");
            }
            Object[] values = decodeArguments(parameterTypes, arguments);
            Object value = method.invoke(instance, values);
            Bundle result = encodeResult(value, method.getReturnType());
            android.util.Log.i(TAG,
                    "OBJECT_INVOKE_SUCCESS session=" + sessionId.substring(0, 8)
                            + " id=" + shortObjectId(objectId)
                            + " resultNull=" + (result == null));
            return result;
        } catch (Throwable error) {
            throw asRemoteException("Invoke client object failed", error);
        }
    }

    @Override
    public void releaseObject(String objectId) throws RemoteException {
        enforceClientCodePermission();

        boolean existedBefore = objectId != null && objects.containsKey(objectId);
        android.util.Log.i(TAG,
                "OBJECT_RELEASE_BEGIN session=" + sessionId.substring(0, 8)
                        + " pid=" + Process.myPid()
                        + " id=" + shortObjectId(objectId)
                        + " exists=" + existedBefore
                        + " size=" + objects.size());

        Object removed = objectId == null ? null : objects.remove(objectId);

        android.util.Log.i(TAG,
                "OBJECT_RELEASE_RESULT session=" + sessionId.substring(0, 8)
                        + " id=" + shortObjectId(objectId)
                        + " removed=" + (removed != null)
                        + " size=" + objects.size());

        if (removed == null) {
            android.util.Log.w(TAG,
                    "OBJECT_RELEASE_REJECTED session=" + sessionId.substring(0, 8)
                            + " id=" + shortObjectId(objectId));
            throw new RemoteException("Unknown or already released object ID");
        }
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
        objects.clear();

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
        handler.postDelayed(() -> System.exit(0), 50L);
    }
}
