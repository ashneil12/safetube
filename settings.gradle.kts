pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        id("com.android.application") version "8.7.3"
        id("org.jetbrains.kotlin.android") version "2.1.0"
        id("org.jetbrains.kotlin.jvm") version "2.1.0"
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "safetube"

// The filtering engine is plain Kotlin so it can be tested (and reused for a
// browser extension or TV app later) without the Android SDK.
include(":core")

// Set SKIP_ANDROID=1 to build and test only the engine on a machine without the Android SDK.
if (System.getenv("SKIP_ANDROID") == null) include(":app")
