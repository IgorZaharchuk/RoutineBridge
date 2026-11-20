plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
val releaseKeyAlias = System.getenv("KEY_ALIAS")
val releaseKeyPassword = System.getenv("KEY_PASSWORD")

val releaseSigningConfigured = listOf(
    keystorePath,
    keystorePassword,
    releaseKeyAlias,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.routinebridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.routinebridge"
        manifestPlaceholders["appLabel"] = "RoutineBridge"
        minSdk = 29
        targetSdk = 35
        versionCode = 7
        versionName = "0.9.5"
    }

    if (releaseSigningConfigured) {
        signingConfigs {
            create("release") {
                storeFile = file(requireNotNull(keystorePath))
                storePassword = requireNotNull(keystorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        getByName("release") {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
        }

        create("experimental") {
            initWith(getByName("release"))
            applicationIdSuffix = ".experimental"
            versionNameSuffix = "-experimental"
            manifestPlaceholders["appLabel"] = "RoutineBridge Experimental"
            matchingFallbacks += listOf("release")

            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
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

tasks.configureEach {
    if (name == "packageRelease" || name == "bundleRelease") {
        doFirst {
            check(releaseSigningConfigured) {
                "Release signing is not configured. Set ANDROID_KEYSTORE_PATH, KEYSTORE_PASSWORD, KEY_ALIAS and KEY_PASSWORD."
            }
        }
    }
}


dependencies {
    implementation(project(":root-core"))
}
