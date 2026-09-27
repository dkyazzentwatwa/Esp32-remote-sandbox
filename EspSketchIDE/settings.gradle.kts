pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

rootProject.name = "EspSketchIDE"
include(":app")
include(":esptool")

// P0 spike app, built only on request: ./gradlew -Pspike :spike-execprobe:assembleDebug
if (providers.gradleProperty("spike").isPresent) {
    include(":spike-execprobe")
    project(":spike-execprobe").projectDir = file("spike/execprobe")
}
