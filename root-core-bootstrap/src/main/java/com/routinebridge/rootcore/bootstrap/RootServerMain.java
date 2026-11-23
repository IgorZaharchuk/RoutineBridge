package com.routinebridge.rootcore.bootstrap;

import android.content.Context;
import android.os.Looper;
import android.os.UserHandle;

import java.lang.reflect.Method;

public final class RootServerMain {

    private RootServerMain() {}

    public static void main(String[] args) {
        try {
            if (args == null || args.length != 5) {
                throw new IllegalArgumentException(
                        "Expected package, client UID, user ID, session ID and policy"
                );
            }

            String packageName = args[0];
            int clientUid = Integer.parseInt(args[1]);
            int userId = Integer.parseInt(args[2]);
            String sessionId = args[3];
            String encodedPolicy = args[4];
            if (sessionId.isEmpty()) {
                throw new IllegalArgumentException("Session ID is empty");
            }

            Looper.prepareMainLooper();

            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Method systemMainMethod = activityThreadClass.getDeclaredMethod("systemMain");
            systemMainMethod.setAccessible(true);
            Object activityThread = systemMainMethod.invoke(null);

            Method getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext");
            getSystemContext.setAccessible(true);
            Context systemContext = (Context) getSystemContext.invoke(activityThread);

            Context packageContext = createPackageContext(systemContext, packageName, userId);

            ClassLoader loader = packageContext.getClassLoader();
            Class<?> serverClass = loader.loadClass(
                    "com.routinebridge.rootcore.internal.RootCoreServer"
            );

            Object server = serverClass
                    .getConstructor(
                            Context.class,
                            int.class,
                            String.class,
                            String.class
                    )
                    .newInstance(
                            packageContext,
                            clientUid,
                            sessionId,
                            encodedPolicy
                    );

            serverClass.getMethod("start").invoke(server);

            Looper.loop();
        } catch (Throwable error) {
            android.util.Log.e("RoutineBridgeRootCore", "Root server bootstrap failed", error);
            System.exit(70);
        }
    }

    private static Context createPackageContext(
            Context systemContext,
            String packageName,
            int userId
    ) throws Exception {
        try {
            Method userHandleOf = UserHandle.class.getDeclaredMethod("of", int.class);
            userHandleOf.setAccessible(true);
            UserHandle user = (UserHandle) userHandleOf.invoke(null, userId);

            Method createForUser = Context.class.getMethod(
                    "createPackageContextAsUser",
                    String.class,
                    int.class,
                    UserHandle.class
            );

            return (Context) createForUser.invoke(
                    systemContext,
                    packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY,
                    user
            );
        } catch (Throwable ignored) {
            return systemContext.createPackageContext(
                    packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY
            );
        }
    }
}
