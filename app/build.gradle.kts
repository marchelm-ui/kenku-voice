plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.kenku.voice"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kenku.voice"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "4.8." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
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

    // Lint is a zero-warning gate: any new warning fails the build. Checks that
    // conflict with deliberate product decisions are disabled, with the reason.
    lint {
        warningsAsErrors = true
        // English-only is the product: a kenku speaking English in many stolen accents.
        disable += "SetTextI18n"
        // Built for Marcus's Pixel; arm64-only is deliberate (saves ~100 MB), ChromeOS is a non-goal.
        disable += "ChromeOsAbiSupport"
        // targetSdk 36 + edge-to-edge sits in the backlog; it needs a visual check on the real phone first.
        disable += "OldTargetApi"
        // Dependency bumps are deliberate per-cycle decisions: the newest androidx.core
        // needs the AGP 9 preview line, which this app doesn't ride.
        disable += "GradleDependency"
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
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    // sherpa-onnx (offline speech engine), downloaded by the build workflow.
    implementation(files("libs/sherpa-onnx.aar"))
    testImplementation("junit:junit:4.13.2")
    // The real org.json, so LineBook's JSON round-trip runs in JVM tests
    // (the Android SDK stub version throws "not mocked").
    testImplementation("org.json:json:20240303")
}
