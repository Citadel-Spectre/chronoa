import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.chronoa"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.chronoa"
        minSdk = 21 // Android 5.0, covers practically every phone in use
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    // The release key lives outside the project (../chronoa-signing), so it's never shared
    // with the code. Without it, release builds fall back to the debug key.
    val keyProps = Properties().apply {
        val f = rootProject.file("../chronoa-signing/keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        create("release") {
            if (keyProps.isNotEmpty()) {
                storeFile = file(keyProps.getProperty("storeFile"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        // Test builds use the same key, so they install over a release build and back.
        debug {
            if (keyProps.isNotEmpty()) signingConfig = signingConfigs.getByName("release")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = if (keyProps.isNotEmpty()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
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

// No dependencies on purpose: only the Android framework and the Kotlin standard library.
