plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kenku.voice"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kenku.voice"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "4.3." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
        // Pixel phones are 64-bit ARM; leaving out other chips keeps the app ~100 MB smaller.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildFeatures {
        buildConfig = true
    }

    // Keep the voice model uncompressed so it loads quickly.
    androidResources {
        noCompress += listOf("onnx", "bin", "ogg")
    }

    packaging {
        jniLibs { useLegacyPackaging = false }
    }

    // A fixed key so each new build installs over the old one without losing saved lines.
    signingConfigs {
        create("kenku") {
            storeFile = file("kenku.keystore")
            storePassword = "kenkuvoice"
            keyAlias = "kenku"
            keyPassword = "kenkuvoice"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("kenku")
        }
        debug {
            signingConfig = signingConfigs.getByName("kenku")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        // The voice library is compiled with a newer Kotlin; its classes are still compatible.
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    // sherpa-onnx (offline speech engine), downloaded by the build workflow.
    implementation(files("libs/sherpa-onnx.aar"))
    testImplementation("junit:junit:4.13.2")
}
