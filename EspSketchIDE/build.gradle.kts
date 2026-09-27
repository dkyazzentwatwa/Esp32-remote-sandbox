plugins {
    id("com.android.application") version "9.4.1" apply false
    // Pure JVM modules (esptool, later buildengine). Matches the Kotlin AGP 9.4.1 bundles.
    id("org.jetbrains.kotlin.jvm") version "2.2.10" apply false
}
