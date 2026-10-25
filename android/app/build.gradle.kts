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
        // Experiment (user request, 2026-10-25): raised from the D9 value 28 to
        // the newest platform (compileSdk 36) to observe what changes on device.
        // D9 is NOT overturned by this — chroot-ng never calls execve (it maps
        // the guest ELF itself via its userland loader) and the packaged binary
        // starts from nativeLibraryDir, which is executable at any targetSdk.
        // Record the device findings in PLAN.md D9 before treating this as final.
        targetSdk = 36
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
            // chroot-ng ships as `libchrootng.so` only so that the installer
            // extracts it into `nativeLibraryDir`; it is a static executable,
            // not a library. Keep the strip task away from it — its symbols are
            // the only thing that makes an on-device crash readable.
            keepDebugSymbols += "**/libchrootng.so"
        }
    }
}

dependencies {
    // JVM unit tests for the pure input encoder (`src/test/java`); the Android
    // classes themselves need a device, so nothing heavier is pulled in.
    testImplementation("junit:junit:4.13.2")
}

// The Rust core reaches the APK through src/main/jniLibs/arm64-v8a/
// (produced by the CI Rust build; never committed — see .gitignore).
