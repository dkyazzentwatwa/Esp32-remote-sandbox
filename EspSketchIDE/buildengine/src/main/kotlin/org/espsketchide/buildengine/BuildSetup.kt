package org.espsketchide.buildengine

import java.io.File

/**
 * An installed Arduino platform (core), e.g. `.../hardware/esp32/3.3.12`, plus where each tool
 * it depends on lives. [tools] maps tool name and `name-version` to directories, like
 * arduino-cli's `runtime.tools.*.path`.
 */
class Platform(val dir: File, val vendor: String, val arch: String, val tools: Map<String, File>) {
    val properties: Properties by lazy { Properties.load(File(dir, "platform.txt")) }
    val boards: BoardCatalog by lazy { BoardCatalog.parse(Properties.load(File(dir, "boards.txt"))) }
}

/** What to build and where. */
class BuildRequest(
    val platform: Platform,
    val boardId: String,
    val menuSelection: Map<String, String> = emptyMap(),
    val sketchDir: File,
    val buildDir: File,
    val timeSeconds: Long = System.currentTimeMillis() / 1000,
) {
    val sketchName: String get() = sketchDir.name
    val projectName: String get() = "$sketchName.ino"

    /** e.g. `esp32:esp32:esp32` or `esp32:esp32:esp32:PartitionScheme=huge_app`. */
    val fqbn: String
        get() {
            val base = "${platform.vendor}:${platform.arch}:$boardId"
            if (menuSelection.isEmpty()) return base
            return base + ":" + menuSelection.entries.joinToString(",") { "${it.key}=${it.value}" }
        }
}

/**
 * Build properties as arduino-cli assembles them: platform.txt, overridden by the board (with
 * menu choices), plus the runtime/build keys the IDE injects.
 */
object BuildProperties {

    /** Arduino IDE version the recipes see as `{runtime.ide.version}` (-DARDUINO=...). */
    const val IDE_VERSION = "10607"

    fun assemble(request: BuildRequest): Properties {
        val platform = request.platform
        val board = platform.boards[request.boardId].resolve(request.menuSelection)
        var props = platform.properties + board

        val injected = linkedMapOf(
            "runtime.platform.path" to platform.dir.path,
            "build.board.platform.path" to platform.dir.path,
            "build.core.platform.path" to platform.dir.path,
            "runtime.hardware.path" to platform.dir.parentFile.path,
            "runtime.os" to "linux",
            "runtime.ide.version" to IDE_VERSION,
            "ide_version" to IDE_VERSION,
            "software" to "ARDUINO",
            "build.arch" to platform.arch.uppercase(),
            "build.fqbn" to request.fqbn,
            "build.path" to request.buildDir.path,
            "build.project_name" to request.projectName,
            "build.source.path" to request.sketchDir.path,
            "sketch_path" to request.sketchDir.path,
            "build.system.path" to File(platform.dir, "system").path,
            "build.library_discovery_phase" to "0",
            "extra.time.utc" to request.timeSeconds.toString(),
            "extra.time.local" to request.timeSeconds.toString(),
            "extra.time.zone" to "0",
            "extra.time.dst" to "0",
        )
        platform.tools.forEach { (name, dir) -> injected["runtime.tools.$name.path"] = dir.path }
        props = props.with(*injected.toList().toTypedArray())

        val core = props["build.core"] ?: throw BuildException("Board ${request.boardId} has no build.core")
        props = props.with("build.core.path" to File(platform.dir, "cores/$core").path)
        props["build.variant"]?.let { props = props.with("build.variant.path" to File(platform.dir, "variants/$it").path) }
        return props
    }
}
