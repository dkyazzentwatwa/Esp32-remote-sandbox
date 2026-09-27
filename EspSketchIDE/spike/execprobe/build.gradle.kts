// P0 spike app (throwaway). Only included with -Pspike; see spike/README.md.
plugins {
    id("com.android.application")
}

val ndkDir = providers.environmentVariable("ANDROID_NDK_HOME")
val toolchainDirs = providers.gradleProperty("toolchainDirs") // abi=dir,abi=dir
val nativeOut = layout.buildDirectory.dir("generated/jniLibs")

/** Toolchain files -> the lib*.so names Android will install into nativeLibraryDir. */
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

val buildProbes by tasks.registering {
    val ndk = ndkDir
    val out = nativeOut
    val toolchains = toolchainDirs
    val cDir = file("src/main/c")
    inputs.dir(cDir)
    inputs.property("toolchains", toolchains.orElse(""))
    outputs.dir(out)
    doLast {
        val llvm = File(ndk.get(), "toolchains/llvm/prebuilt/linux-x86_64/bin")
        val abis = mapOf("arm64-v8a" to "aarch64-linux-android26", "armeabi-v7a" to "armv7a-linux-androideabi26")
        for ((abi, triple) in abis) {
            val dir = out.get().dir(abi).asFile.apply { mkdirs() }
            fun cc(vararg args: String) {
                val p = ProcessBuilder(listOf(File(llvm, "$triple-clang").path) + args).inheritIO().start()
                check(p.waitFor() == 0) { "clang failed for $abi: ${args.toList()}" }
            }
            cc("-O2", "-o", File(dir, "libprobehello.so").path, File(cDir, "hello.c").path)
            cc("-O2", "-o", File(dir, "libprobedl.so").path, File(cDir, "dlprobe.c").path)
            cc("-O2", "-shared", "-fPIC", "-o", File(dir, "libprobeplugin.so").path, File(cDir, "plugin.c").path)
        }
        toolchains.orNull?.split(',')?.filter { it.isNotBlank() }?.forEach { entry ->
            val (abi, path) = entry.split('=', limit = 2)
            val dir = out.get().dir(abi).asFile.apply { mkdirs() }
            toolchainNames.forEach { (from, to) -> File(path, from).copyTo(File(dir, to), overwrite = true) }
        }
    }
}

android {
    namespace = "org.espsketchide.spike"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.espsketchide.spike.execprobe"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "p0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Executables must exist as real files in nativeLibraryDir to be runnable.
    packaging { jniLibs { useLegacyPackaging = true } }

    sourceSets["main"].jniLibs.directories.add(nativeOut.get().asFile.path)
}

tasks.named("preBuild") { dependsOn(buildProbes) }

dependencies {
    implementation("androidx.appcompat:appcompat:1.8.0")
}
