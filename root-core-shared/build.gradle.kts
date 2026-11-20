plugins {
    id("com.android.library")
}

android {
    namespace = "com.routinebridge.rootcore.shared"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
    }

    buildFeatures {
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
