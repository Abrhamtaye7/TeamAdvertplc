plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.teamadvert.chesscam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.teamadvert.chesscam"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        // Stockfish is only built for 64-bit ARM, which covers current Android phones.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        // A shared, non-secret key so every build (local or CI) can update the
        // installed app. Use your own key before publishing anywhere.
        getByName("debug") {
            storeFile = file("debug.keystore")
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        jniLibs {
            // The engine runs as an executable, so it has to be extracted to disk on install.
            useLegacyPackaging = true
            keepDebugSymbols += "**/libstockfish.so"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
}
