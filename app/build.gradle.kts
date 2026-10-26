plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Build-Nummer kommt von GitHub Actions, lokal 1
val buildNumber = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.john.nitro"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.john.nitro"
        minSdk = 29
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.$buildNumber"
    }

    // Fester Schlüssel im Repo, damit sich neue Versionen über die alte installieren lassen
    signingConfigs {
        getByName("debug") {
            storeFile = file("nitro-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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
