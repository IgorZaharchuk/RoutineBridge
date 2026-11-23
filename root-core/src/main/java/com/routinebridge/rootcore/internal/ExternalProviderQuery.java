package com.routinebridge.rootcore.internal;

import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import com.routinebridge.rootcore.RootCorePolicy;

import java.util.ArrayList;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

final class ExternalProviderQuery {
    private static final String TAG = "RoutineBridgeRootCore";

    private ExternalProviderQuery() {}

    static Bundle query(
            String uri,
            String[] projection,
            int clientUid,
            RootCorePolicy policy
    ) throws RemoteException {

        if (uri == null || uri.trim().isEmpty()) {
            throw new RemoteException("Provider URI is empty");
        }

        Uri parsedUri = Uri.parse(uri);

        if (!"content".equals(parsedUri.getScheme())) {
            throw new RemoteException("Only content:// Provider URIs are accepted");
        }

        String authority = parsedUri.getAuthority();
        if (authority == null || !policy.allowsProviderAuthority(authority)) {
            throw new RemoteException(
                    "Provider authority is not allowed: " + authority
            );
        }

        int userId = clientUid / 100000;
        IBinder externalToken = new Binder();

        Object activityManager = null;
        Object provider = null;
        Cursor cursor = null;
        boolean providerAcquired = false;
        Throwable operationError = null;

        android.util.Log.i(TAG, "External Provider query started: " + uri);

        try {
            activityManager = getActivityManager();

            Method acquireMethod = findMethod(
                    activityManager.getClass(),
                    "getContentProviderExternal",
                    String.class, int.class, IBinder.class, String.class
            );

            Object holder;

            if (acquireMethod != null) {
                holder = invokeReflective(
                        acquireMethod, activityManager,
                        authority, userId, externalToken, "*routinebridge*"
                );
            } else {
                acquireMethod = findMethod(
                        activityManager.getClass(),
                        "getContentProviderExternal",
                        String.class, int.class, IBinder.class
                );

                if (acquireMethod == null) {
                    throw new NoSuchMethodException(
                            "ActivityManager.getContentProviderExternal not found"
                    );
                }

                holder = invokeReflective(
                        acquireMethod, activityManager,
                        authority, userId, externalToken
                );
            }

            if (holder == null) {
                throw new RemoteException(
                        "ActivityManager returned no Provider holder: " + authority
                );
            }

            providerAcquired = true;

            java.lang.reflect.Field providerField;
            try {
                providerField = holder.getClass().getField("provider");
            } catch (NoSuchFieldException error) {
                providerField = holder.getClass().getDeclaredField("provider");
            }

            providerField.setAccessible(true);
            provider = providerField.get(holder);

            if (provider == null) {
                throw new RemoteException(
                        "External Provider holder contains no provider: " + authority
                );
            }

            Method queryMethod = findProviderQueryMethod(provider.getClass());

            Class<?>[] parameters = queryMethod.getParameterTypes();
            Object[] queryArguments;

            if ("android.content.AttributionSource".equals(
                    parameters[0].getName())) {

                Class<?> attributionClass = parameters[0];
                Method attributionMethod =
                        attributionClass.getMethod("myAttributionSource");
                Object attribution = invokeReflective(attributionMethod, null);

                queryArguments = new Object[] {
                        attribution, parsedUri, projection, new Bundle(), null
                };

            } else if (parameters.length == 5) {
                queryArguments = new Object[] {
                        "root", parsedUri, projection, new Bundle(), null
                };

            } else {
                queryArguments = new Object[] {
                        "root", parsedUri, projection, null, null, null, null
                };
            }

            android.util.Log.i(TAG, "Calling external Provider query: "
                    + queryMethod.toGenericString());

            Object result = invokeReflective(
                    queryMethod, provider, queryArguments
            );

            if (result == null) {
                throw new RemoteException("ContentProvider returned a null Cursor");
            }

            if (!(result instanceof Cursor)) {
                throw new RemoteException(
                        "Unexpected Provider query result: " + result.getClass().getName()
                );
            }

            cursor = (Cursor) result;

            String[] columns = cursor.getColumnNames();
            Bundle response = new Bundle();
            response.putStringArray("columns", columns);

            ArrayList<Bundle> rows = new ArrayList<>();

            while (cursor.moveToNext()) {
                Bundle row = new Bundle();

                for (int i = 0; i < columns.length; i++) {
                    row.putString(
                            "c" + i,
                            cursor.isNull(i) ? null : cursor.getString(i)
                    );
                }

                rows.add(row);
            }

            response.putParcelableArrayList("rows", rows);

            android.util.Log.i(TAG, "External Provider query completed: rows="
                    + rows.size() + ", columns=" + columns.length);

            return response;

        } catch (RemoteException error) {
            operationError = error;
            android.util.Log.e(TAG, "External Provider query failed: " + uri, error);
            throw error;

        } catch (Throwable error) {
            RemoteException remoteError = new RemoteException(
                    "External Provider query failed: "
                            + error.getClass().getName() + ": " + error.getMessage()
            );
            remoteError.initCause(error);
            operationError = remoteError;

            android.util.Log.e(TAG, "External Provider query crashed: " + uri, error);
            throw remoteError;

        } finally {
            if (cursor != null) {
                try {
                    cursor.close();
                } catch (Throwable error) {
                    android.util.Log.w(TAG, "Cursor close failed", error);
                }
            }

            if (providerAcquired && activityManager != null) {
                try {
                    Method releaseMethod = findMethod(
                            activityManager.getClass(),
                            "removeContentProviderExternalAsUser",
                            String.class, IBinder.class, int.class
                    );

                    if (releaseMethod != null) {
                        invokeReflective(
                                releaseMethod, activityManager,
                                authority, externalToken, userId
                        );
                    } else {
                        releaseMethod = findMethod(
                                activityManager.getClass(),
                                "removeContentProviderExternal",
                                String.class, IBinder.class
                        );

                        if (releaseMethod == null) {
                            throw new NoSuchMethodException(
                                    "External Provider release method not found"
                            );
                        }

                        invokeReflective(
                                releaseMethod, activityManager,
                                authority, externalToken
                        );
                    }

                    android.util.Log.i(TAG,
                            "External Provider released: " + authority);

                } catch (Throwable releaseError) {
                    android.util.Log.e(TAG,
                            "External Provider release failed: " + authority,
                            releaseError);

                    if (operationError != null) {
                        operationError.addSuppressed(releaseError);
                    } else {
                        RemoteException remoteError = new RemoteException(
                                "Failed to release external Provider: " + authority
                        );
                        remoteError.initCause(releaseError);
                        throw remoteError;
                    }
                }
            }
        }
    }

    private static Object getActivityManager() throws Throwable {
        Class<?> activityManagerClass = Class.forName("android.app.ActivityManager");
        Method method = activityManagerClass.getMethod("getService");
        return invokeReflective(method, null);
    }

    private static Method findMethod(
            Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            return type.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static Method findProviderQueryMethod(Class<?> providerClass)
            throws NoSuchMethodException {

        for (Method method : providerClass.getMethods()) {
            if (!"query".equals(method.getName())) {
                continue;
            }

            Class<?>[] p = method.getParameterTypes();

            if (p.length == 5
                    && p[1] == Uri.class
                    && p[2] == String[].class
                    && p[3] == Bundle.class
                    && "android.os.ICancellationSignal".equals(p[4].getName())
                    && ("android.content.AttributionSource".equals(p[0].getName())
                        || p[0] == String.class)) {
                return method;
            }

            if (p.length == 7
                    && p[0] == String.class
                    && p[1] == Uri.class
                    && p[2] == String[].class
                    && p[3] == String.class
                    && p[4] == String[].class
                    && p[5] == String.class
                    && "android.os.ICancellationSignal".equals(p[6].getName())) {
                return method;
            }
        }

        throw new NoSuchMethodException(
                "Compatible IContentProvider.query() not found in "
                        + providerClass.getName()
        );
    }

    private static Object invokeReflective(
            Method method, Object receiver, Object... arguments
    ) throws Throwable {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            throw cause != null ? cause : error;
        }
    }

}
