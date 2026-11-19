plugins {
    alias(libs.plugins.android.library)
}

// The coordinates apps use to depend on Davy, including from a composite build.
group = "com.gu"

android {
    namespace = "com.gu.davy"
    compileSdk = 37

    defaultConfig {
        minSdk = 23
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        aarMetadata {
            // Davy's API and resources need nothing newer than minSdk, so don't make apps compile
            // against the newest API level it's built with, which AGP requires by default.
            minCompileSdk = 23
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Android's own classes, such as Log, do nothing in local unit tests rather than throw.
        unitTests.isReturnDefaultValues = true
        unitTests.all { test ->
            // The shared spec and fixtures that both the Swift and Kotlin clients are tested against.
            test.systemProperty("davy.specDirectory", rootProject.layout.projectDirectory.dir("../Spec").asFile.absolutePath)
        }
    }
}

kotlin {
    explicitApi()
}

dependencies {
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    // Android's org.json is a stub in local unit tests, so use the real one.
    testImplementation(libs.json)
    testImplementation(libs.snakeyaml)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}
