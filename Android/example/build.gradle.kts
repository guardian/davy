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
