package com.routinebridge;

import android.content.Context;
import android.os.Bundle;
import android.os.Process;

public final class RootCoreClassLoadingProbe {

    public RootCoreClassLoadingProbe() {
    }

    public Bundle inspect(Context context) {
        Bundle result = new Bundle();

        result.putInt("uid", Process.myUid());
        result.putInt("pid", Process.myPid());
        result.putString("class_name", getClass().getName());
        result.putString(
                "class_loader",
                String.valueOf(getClass().getClassLoader())
        );
        result.putString("context_package", context.getPackageName());
        result.putBoolean(
                "same_loader_as_context",
                getClass().getClassLoader() == context.getClassLoader()
        );

        return result;
    }
}
