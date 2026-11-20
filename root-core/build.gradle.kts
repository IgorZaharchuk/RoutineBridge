import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

plugins {
    id("com.android.library")
}

android {
    namespace = "com.routinebridge.rootcore"
    compileSdk = 35

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":root-core-shared"))
}

val generatedRootCoreAssets =
    layout.buildDirectory.dir("generated/rootcore/assets")

android.sourceSets.getByName("main").assets.srcDir(generatedRootCoreAssets)

val bootstrapApk =
    project(":root-core-bootstrap").layout.buildDirectory.file(
        "outputs/apk/debug/root-core-bootstrap-debug.apk"
    )

val extractRootCoreBootstrapDex = tasks.register("extractRootCoreBootstrapDex") {
    dependsOn(":root-core-bootstrap:assembleDebug")
    inputs.file(bootstrapApk)
    outputs.file(generatedRootCoreAssets.map { it.file("main.jar") })

    doLast {
        val apkFile = bootstrapApk.get().asFile
        check(apkFile.isFile) {
            "Root Core bootstrap APK was not produced: $apkFile"
        }

        val output = generatedRootCoreAssets.get().file("main.jar").asFile
        output.parentFile.mkdirs()

        ZipFile(apkFile).use { apk ->
            ZipOutputStream(output.outputStream()).use { jar ->
                val dexEntries = apk.entries().asSequence()
                    .filter { !it.isDirectory && it.name.matches(Regex("classes(\\d*)\\.dex")) }
                    .sortedBy { it.name }
                    .toList()

                check(dexEntries.isNotEmpty()) {
                    "No DEX files found in bootstrap APK"
                }

                dexEntries.forEach { entry ->
                    jar.putNextEntry(ZipEntry(entry.name))
                    apk.getInputStream(entry).use { input -> input.copyTo(jar) }
                    jar.closeEntry()
                }
            }
        }

        println("Root Core bootstrap DEX packaged: ${output.absolutePath}")
    }
}

tasks.named("preBuild").configure {
    dependsOn(extractRootCoreBootstrapDex)
}
