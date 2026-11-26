package com.routinebridge.rootcore.ipc;

import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;

interface IRootCoreManager {
    int ping();
    void connect(IBinder client);
    Bundle query(String uri, in String[] projection);
    Bundle runClassLoadingProbe();
    void launchActivity(in Intent intent);
    oneway void close();
}
