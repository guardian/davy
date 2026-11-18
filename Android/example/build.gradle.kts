import java.io.ByteArrayOutputStream
import javax.inject.Inject

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.gu.davy.example"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.gu.davy.example"
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":davy"))
}

/** The port of the test server in `TestServer/`, which debug builds send events to at localhost. */
val testServerPort = 8080

/**
 * Runs `adb reverse` on every connected emulator and device, so the app reaches the test server on
 * this computer at localhost. Android forgets this whenever the emulator restarts or the device
 * reconnects, so it's redone on every debug build rather than left to be run by hand.
 */
abstract class ReverseTestServerPort @Inject constructor(private val exec: ExecOperations) : DefaultTask() {
    @get:Internal
    abstract val adb: RegularFileProperty

    @get:Input
    abstract val port: Property<Int>

    @TaskAction
    fun reverse() {
        val port = port.get()
        val hint = "run `adb reverse tcp:$port tcp:$port` once it's connected, so the example app reaches the test server"
        val adb = adb.get().asFile
        if (!adb.canExecute()) {
            logger.warn("Couldn't find adb at $adb; $hint")
            return
        }
        val devices = ByteArrayOutputStream()
        exec.exec {
            commandLine(adb, "devices")
            standardOutput = devices
            errorOutput = ByteArrayOutputStream()
            isIgnoreExitValue = true
        }
        // Lines such as "emulator-5554\tdevice", skipping devices that are offline or unauthorized.
        val serials = devices.toString().lines().mapNotNull { line ->
            line.split('\t').takeIf { it.size == 2 && it[1] == "device" }?.first()
        }
        if (serials.isEmpty()) {
            logger.lifecycle("No emulator or device is connected; $hint")
        }
        for (serial in serials) {
            val error = ByteArrayOutputStream()
            val result = exec.exec {
                commandLine(adb, "-s", serial, "reverse", "tcp:$port", "tcp:$port")
                standardOutput = ByteArrayOutputStream()
                errorOutput = error
                isIgnoreExitValue = true
            }
            if (result.exitValue == 0) {
                logger.info("Forwarded port $port on $serial to this computer")
            } else {
                logger.warn("Couldn't forward port $port on $serial to this computer: ${error.toString().trim()}")
            }
        }
    }
}

val reverseTestServerPort = tasks.register<ReverseTestServerPort>("reverseTestServerPort") {
    description = "Lets connected emulators and devices reach the test server at localhost:$testServerPort."
    adb = androidComponents.sdkComponents.adb
    port = testServerPort
}

// Android Studio's Run builds with assembleDebug, and the command line installs with installDebug.
tasks.named { it == "assembleDebug" || it == "installDebug" }.configureEach {
    dependsOn(reverseTestServerPort)
}
