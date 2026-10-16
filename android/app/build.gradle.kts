plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.drosh.lab"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.drosh.lab"
        minSdk = 26
        // Decision D9 — targetSdk 28 is deliberate (Android 10+ exec ban on
        // app data for targetSdk >= 29). Never change silently.
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"

        ndk {
            // Drosh ships arm64-v8a only (real devices); x86_64 for emulator
            // is added when needed.
            abiFilters += setOf("arm64-v8a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        jniLibs {
            // Raw (non-.so-named) executables must exist on disk to be exec'd;
            // this is the documented chroot-ng/Pockr finding. Keep it.
            useLegacyPackaging = true
        }
    }
}

// The Rust core reaches the APK through src/main/jniLibs/arm64-v8a/
// (produced by cargo-ndk in CI; never committed — see .gitignore).
