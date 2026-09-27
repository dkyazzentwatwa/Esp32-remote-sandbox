// ESP ROM serial bootloader client (pure JVM, no Android). Written from Espressif's published
// serial protocol documentation; esptool itself is a behavioural reference only (GPL-2.0).
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.5")
}

tasks.test {
    // Optional QEMU integration test (see QemuFlashTest); skipped unless these are set.
    listOf("ESP_QEMU", "ESP_FLASH_REFERENCE", "ESP_TEST_IMAGES", "ESP_FLASH_OUT").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
}
