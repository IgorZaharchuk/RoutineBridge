plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.routinebridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.routinebridge"
        minSdk = 29
        targetSdk = 35
        versionCode = 6
        versionName = "0.9.4"
    }

    signingConfigs {
        create("release") {
            storeFile = file(
                System.getenv("ANDROID_KEYSTORE_PATH")
                    ?: error("ANDROID_KEYSTORE_PATH is missing")
            )
            storePassword = System.getenv("KEYSTORE_PASSWORD")
                ?: error("KEYSTORE_PASSWORD is missing")
            keyAlias = System.getenv("KEY_ALIAS")
                ?: error("KEY_ALIAS is missing")
            keyPassword = System.getenv("KEY_PASSWORD")
                ?: error("KEY_PASSWORD is missing")
        }
    }

    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
