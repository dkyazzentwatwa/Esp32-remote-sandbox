// On-device Arduino build engine (pure JVM, no Android): properties, recipes, preprocessing,
// library discovery and the ESP32 image tools. arduino-cli is a behavioural reference only.
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
    // Optional golden tests against an installed ESP32 core and arduino-cli output (see GoldenTest).
    listOf("ESP32_CORE_DIR", "ESP32_REFERENCE_DIR", "ESP32_TOOLCHAIN_BIN").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
}
