plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.safetube"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.safetube"
        // Fire OS 5 (2015 Fire tablets) is API 22.
        minSdk = 22
        targetSdk = 35
        versionCode = 11
        versionName = "0.6.4"
    }

    signingConfigs {
        // CI signs release builds with a key from repository secrets; see README.
        create("release") {
            val ks = System.getenv("SAFETUBE_KEYSTORE")
            if (ks != null) {
                storeFile = file(ks)
                storePassword = System.getenv("SAFETUBE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("SAFETUBE_KEY_ALIAS")?.ifBlank { null } ?: "safetube"
                keyPassword = System.getenv("SAFETUBE_KEY_PASSWORD")?.ifBlank { null } ?: storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("SAFETUBE_KEYSTORE") != null) {
                signingConfigs.getByName("release")
            } else {
                // Unsigned-key fallback so anyone can build an installable APK.
                signingConfigs.getByName("debug")
            }
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

dependencies {
    implementation(project(":core"))
    // Lets the filter script run before YouTube's own scripts on every page.
    implementation("androidx.webkit:webkit:1.12.1")
}
