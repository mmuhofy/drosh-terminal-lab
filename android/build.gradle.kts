plugins {
    id("com.android.application") version "8.9.1" apply false
    // Kotlin 2.4.0 — the version DroshKeyboard (the sibling project) builds with,
    // so the lab and Drosh stay on one toolchain.
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
}
