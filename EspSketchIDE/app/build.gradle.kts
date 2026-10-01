plugins {
    id("com.android.application")
}

// Optional: bundle the Android-hosted xtensa toolchain (toolchain/build-android.sh output) as
// lib*.so files so the app can compile. -PtoolchainDirs=arm64-v8a=<dir>,armeabi-v7a=<dir>.
// Without it the app builds as an editor only and hides compile/upload.
val toolchainDirs = providers.gradleProperty("toolchainDirs").orNull
    ?.split(',')?.filter { it.isNotBlank() }?.associate { it.substringBefore('=') to file(it.substringAfter('=')) }
    .orEmpty()
val bundledToolchainDir = layout.buildDirectory.dir("generated/toolchain-jniLibs")

/** Toolchain files -> the lib*.so names Android installs into nativeLibraryDir (see ToolchainInstaller). */
val toolchainNames = mapOf(
    "bin/xtensa-esp-elf-gcc" to "libxtgcc.so",
    "bin/xtensa-esp-elf-g++" to "libxtgxx.so",
    "bin/xtensa-esp-elf-as" to "libxtas.so",
    "bin/xtensa-esp-elf-ld" to "libxtld.so",
    "bin/xtensa-esp-elf-ar" to "libxtar.so",
    "bin/xtensa-esp-elf-objcopy" to "libxtobjcopy.so",
    "bin/xtensa-esp-elf-size" to "libxtsize.so",
    "libexec/gcc/xtensa-esp-elf/14.2.0/cc1" to "libxtcc1.so",
    "libexec/gcc/xtensa-esp-elf/14.2.0/cc1plus" to "libxtcc1plus.so",
    "libexec/gcc/xtensa-esp-elf/14.2.0/collect2" to "libxtcollect2.so",
    "lib/xtensa_esp32.so" to "libxtensa_esp32.so",
)

val bundleToolchain by tasks.registering {
    val dirs = toolchainDirs
    val out = bundledToolchainDir
    inputs.property("toolchainDirs", dirs.mapValues { it.value.path })
    outputs.dir(out)
    doLast {
        out.get().asFile.deleteRecursively()
        dirs.forEach { (abi, dir) ->
            toolchainNames.forEach { (from, to) ->
                File(dir, from).copyTo(out.get().dir(abi).file(to).asFile, overwrite = true)
            }
        }
    }
}

android {
    namespace = "org.espsketchide.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.espsketchide.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "TOOLCHAIN_BUNDLED", toolchainDirs.isNotEmpty().toString())
        // Where "Download" gets the board pack (-PpackUrl, -PpackSha256); empty hides the button.
        buildConfigField("String", "PACK_URL", "\"${providers.gradleProperty("packUrl").getOrElse("")}\"")
        buildConfigField("String", "PACK_SHA256", "\"${providers.gradleProperty("packSha256").getOrElse("")}\"")
        if (toolchainDirs.isNotEmpty()) ndk { abiFilters += toolchainDirs.keys }
    }

    // Executables must exist as real files in nativeLibraryDir to be runnable.
    packaging { jniLibs { useLegacyPackaging = true } }
    sourceSets["main"].jniLibs.directories.add(bundledToolchainDir.get().asFile.path)

    // Release signing comes from environment variables (set by the release workflow from repo
    // secrets). Without them the release APK is built unsigned, so forks and PRs still build.
    val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
    signingConfigs {
        if (keystorePath != null) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

tasks.named("preBuild") { dependsOn(bundleToolchain) }

dependencies {
    implementation(project(":buildengine"))
    implementation(project(":esptool"))
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.12")

    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.fragment:fragment-ktx:1.9.1")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Code editor (sora-editor, LGPL-2.1, used unmodified as a library). language-textmate
    // highlights sketches with VS Code's C++ grammar plus our Arduino injection grammar
    // (assets/textmate). See editor/EditorLanguages.kt and assets/licenses/NOTICES.md.
    implementation("io.github.Rosemoe.sora-editor:editor:0.23.6")
    implementation("io.github.Rosemoe.sora-editor:language-textmate:0.23.6")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.truth:truth:1.4.5")
    testImplementation("org.json:json:20240303")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
}
