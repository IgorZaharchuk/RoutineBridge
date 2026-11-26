package com.routinebridge.rootcore.ipc;

import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

interface IRootCoreManager {
    int ping();
    void connect(IBinder client);
    Bundle query(String uri, in String[] projection);
    String createObject(String className, in String[] constructorTypes, in Bundle arguments);
    Bundle invokeObject(String objectId, String methodName, in String[] parameterTypes, in Bundle arguments);
    void releaseObject(String objectId);
    void launchActivity(in Intent intent);
    oneway void close();
}
