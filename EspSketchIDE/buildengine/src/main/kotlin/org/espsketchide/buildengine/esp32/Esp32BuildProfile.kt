package org.espsketchide.buildengine.esp32

import org.espsketchide.buildengine.BuildContext
import org.espsketchide.buildengine.BuildException
import org.espsketchide.buildengine.BuildProfile
import org.espsketchide.buildengine.Builder
import java.io.File

/**
 * Arduino-ESP32 3.x build steps that platform.txt runs through bash, gen_esp32part.py and
 * esptool, implemented natively so they work on Android.
 */
object Esp32BuildProfile : BuildProfile {

    override val handledHooks = (1..8).map { "recipe.hooks.prebuild.$it" }.toSet() +
        setOf("recipe.hooks.core.prebuild.1", "recipe.hooks.core.postbuild.1") +
        (1..5).map { "recipe.hooks.objcopy.postobjcopy.$it" }

    override val handledObjcopyRecipes = setOf("recipe.objcopy.partitions.bin.pattern", "recipe.objcopy.bin.pattern")

    private const val PARTITIONS_OFFSET = 0x8000
    private const val OTADATA_OFFSET = 0xE000
    private const val APP_OFFSET = 0x10000

    override fun prebuild(ctx: BuildContext) {
        val sketch = ctx.request.sketchDir
        val platformDir = ctx.request.platform.dir
        val variant = File(ctx.expanded("build.variant.path"))
        val sdk = File(ctx.expanded("compiler.sdk.path"))

        // Partition table: the menu's scheme, overridden by the variant's, then the sketch's own.
        val partitionCandidates = listOf(
            File(platformDir, "tools/partitions/${ctx.expanded("build.partitions")}.csv"),
            File(variant, "${ctx.expanded("build.custom_partitions")}.csv"),
            File(sketch, "partitions.csv"),
        )
        val partitions = partitionCandidates.lastOrNull { it.isFile }
            ?: throw BuildException("No partitions.csv for scheme ${ctx.expanded("build.partitions")}")
        Builder.writeIfChanged(ctx.file("partitions.csv"), partitions.readBytes())

        // Bootloader: the sketch's, else the variant's, else built from the SDK's bootloader ELF.
        val bootloaderOut = ctx.file("${ctx.projectName}.bootloader.bin")
        val sketchBootloader = File(sketch, "bootloader.bin")
        val variantBootloader = File(variant, "${ctx.expanded("build.custom_bootloader")}.bin")
        val bootloader = when {
            sketchBootloader.isFile -> sketchBootloader.readBytes()
            variantBootloader.isFile -> variantBootloader.readBytes()
            else -> {
                val elf = File(sdk, "bin/bootloader_${ctx.expanded("build.boot")}_${ctx.expanded("build.boot_freq")}.elf")
                if (!elf.isFile) throw BuildException("Bootloader ${elf.name} is missing from the board pack")
                Esp32Image.elf2image(elf.readBytes(), imageOptions(ctx, withSha = false))
            }
        }
        Builder.writeIfChanged(bootloaderOut, bootloader)

        // build_opt.h: the sketch's, else keep/create an empty one. file_opts starts empty.
        val sketchOpts = File(sketch, ctx.expanded("build.opt.name"))
        val buildOpts = File(ctx.expanded("build.opt.path"))
        if (sketchOpts.isFile) Builder.writeIfChanged(buildOpts, sketchOpts.readBytes())
        else if (!buildOpts.isFile) buildOpts.writeText("")
        Builder.writeIfChanged(File(ctx.expanded("file_opts.path")), "")

        Builder.writeIfChanged(ctx.file("sdkconfig"), File(sdk, "sdkconfig").readBytes())
    }

    // file_opts marks the core build; written only when it changes so objects stay reusable.
    override fun corePrebuild(ctx: BuildContext) =
        Builder.writeIfChanged(File(ctx.expanded("file_opts.path")), "-DARDUINO_CORE_BUILD\n")

    override fun corePostbuild(ctx: BuildContext) = Builder.writeIfChanged(File(ctx.expanded("file_opts.path")), "")

    override fun objcopy(ctx: BuildContext, elf: File): List<Pair<Int, File>> {
        val name = ctx.projectName
        val partitionsBin = ctx.file("$name.partitions.bin")
        Builder.writeIfChanged(partitionsBin, PartitionTable.fromCsv(ctx.file("partitions.csv").readText()))
        val app = ctx.file("$name.bin")
        app.writeBytes(Esp32Image.elf2image(elf.readBytes(), imageOptions(ctx, withSha = true)))

        if (File(ctx.buildDir, "libraries/Insights").isDirectory) {
            throw BuildException("The ESP Insights library isn't supported by this app yet")
        }
        val srModels = File(ctx.expanded("compiler.sdk.path"), "esp_sr/srmodels.bin")
        if (File(ctx.buildDir, "libraries/ESP_SR").isDirectory && srModels.isFile) {
            Builder.writeIfChanged(ctx.file("srmodels.bin"), srModels.readBytes())
        }

        val bootApp0 = ctx.file("boot_app0.bin")
        Builder.writeIfChanged(bootApp0, File(ctx.request.platform.dir, "tools/partitions/boot_app0.bin").readBytes())
        val bootloaderAddr = Integer.decode(ctx.expanded("build.bootloader_addr"))
        val bootloader = ctx.file("$name.bootloader.bin")
        val images = listOf(
            bootloaderAddr to bootloader,
            PARTITIONS_OFFSET to partitionsBin,
            OTADATA_OFFSET to bootApp0,
            APP_OFFSET to app,
        )
        val flashSize = flashSizeBytes(ctx.expanded("build.flash_size"))
        ctx.file("$name.merged.bin").writeBytes(Esp32Image.mergeBin(images.map { it.first to it.second.readBytes() }, flashSize))

        ctx.file("flash_args").writeText(
            "--flash-mode ${ctx.expanded("build.flash_mode")} --flash-freq ${ctx.expanded("build.img_freq")} " +
                "--flash-size ${ctx.expanded("build.flash_size")}\n" +
                "${ctx.expanded("build.bootloader_addr")} $name.bootloader.bin\n" +
                "0x8000 $name.partitions.bin\n" +
                "0xe000 boot_app0.bin\n" +
                "0x10000 $name.bin\n"
        )
        return images
    }

    private fun imageOptions(ctx: BuildContext, withSha: Boolean) = Esp32Image.Options(
        flashMode = ctx.expanded("build.flash_mode"),
        flashFreq = ctx.expanded("build.img_freq"),
        flashSize = ctx.expanded("build.flash_size"),
        elfSha256Offset = if (withSha) 0xB0 else null,
    )

    fun flashSizeBytes(size: String): Int {
        val mb = size.removeSuffix("MB").toIntOrNull() ?: throw BuildException("Unknown flash size $size")
        return mb * 1024 * 1024
    }
}
