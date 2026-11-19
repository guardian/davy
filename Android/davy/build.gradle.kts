plugins {
    alias(libs.plugins.android.library)
    `maven-publish`
    // Generates api/api.txt, the public API, which the release workflow uses to choose the next version.
    alias(libs.plugins.metalava)
}

// The coordinates apps use to depend on Davy, from Maven Central or a composite build.
group = "com.gu"
// The latest release. The release workflow sets the new version here before building it.
version = rootProject.file("version.txt").readText().trim()

metalava {
    filename = "api/api.txt"
}

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

    // A single "release" variant, with sources and Javadoc jars, which Maven Central requires.
    publishing {
        singleVariant("release") {
            withSourcesJar()
            withJavadocJar()
        }
    }
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "davy"
            pom {
                name = "Davy"
                description = "A lightweight, privacy-minded telemetry client: log small, anonymous events, " +
                    "stored on the device and delivered to an endpoint you run."
                url = "https://github.com/guardian/davy"
                licenses {
                    license {
                        name = "Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
                developers {
                    developer {
                        id = "guardian"
                        name = "Guardian News & Media"
                        url = "https://github.com/guardian"
                    }
                }
                organization {
                    name = "Guardian News & Media"
                    url = "https://www.theguardian.com"
                }
                scm {
                    connection = "scm:git:github.com/guardian/davy.git"
                    developerConnection = "scm:git:ssh://github.com/guardian/davy.git"
                    url = "https://github.com/guardian/davy"
                }
            }
            afterEvaluate {
                from(components["release"])
            }
        }
    }
    repositories {
        // Where the release workflow stages artifacts before signing and uploading them:
        // `./gradlew :davy:publishReleasePublicationToCustomRepository -Prepo.local=<path>`.
        maven {
            name = "custom"
            url = uri(providers.gradleProperty("repo.local").getOrElse(layout.buildDirectory.dir("custom").get().asFile.path))
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
