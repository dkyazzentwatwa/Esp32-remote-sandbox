package org.espsketchide.spike

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.system.Os
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * P0 spike (throwaway). Answers, on a real phone:
 *  - can we run executables shipped as lib*.so, directly, through a link, and nested (exec)?
 *  - can they dlopen a plugin (as gcc/as/ld do with xtensa_esp32.so)?
 *  - does the Android-built xtensa toolchain start, and how long does Blink take to compile?
 * Results can be shared as text.
 */
class ProbeActivity : AppCompatActivity() {

    private lateinit var output: TextView
    private val log = StringBuilder()

    private val nativeDir get() = File(applicationInfo.nativeLibraryDir)
    private val tc get() = File(filesDir, "tc")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply { setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE; textSize = 11f }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun button(label: String, action: () -> Unit) = buttons.addView(Button(this).apply {
            text = label
            setOnClickListener { thread { action(); runOnUiThread { output.text = log } } }
        })
        button("Probes") { runProbes() }
        button("Compile Blink") { compileBlink() }
        button("Share") { share() }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 96, 24, 24)
            addView(buttons)
            addView(ScrollView(this@ProbeActivity).apply { addView(output) })
        })
        say("device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ABIs ${Build.SUPPORTED_ABIS.joinToString()}")
        say("nativeLibraryDir: ${nativeDir.path}")
        output.text = log
    }

    private fun say(line: String) {
        synchronized(log) { log.appendLine(line) }
        runOnUiThread { output.text = log }
    }

    private fun run(cmd: List<String>, env: Map<String, String> = emptyMap(), dir: File? = null): Pair<Int, String> {
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment()["TMPDIR"] = cacheDir.path
        pb.environment().putAll(env)
        dir?.let { pb.directory(it) }
        val p = pb.start()
        val text = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(10, TimeUnit.MINUTES)) p.destroyForcibly()
        return p.exitValue() to text.trim()
    }

    private fun check(name: String, cmd: List<String>, expect: String, env: Map<String, String> = emptyMap()) {
        val result = try {
            val (code, text) = run(cmd, env)
            if (code == 0 && text.contains(expect)) "PASS" else "FAIL exit=$code: ${text.take(300)}"
        } catch (e: Exception) {
            "FAIL ${e.javaClass.simpleName}: ${e.message}"
        }
        say("[$name] $result")
    }

    private fun link(target: File, at: File) {
        at.parentFile?.mkdirs()
        at.delete()
        Os.symlink(target.path, at.path)
    }

    private fun runProbes() {
        say("--- P0.1 exec/dlopen probes")
        val hello = File(nativeDir, "libprobehello.so")
        val probeDir = File(filesDir, "probe")
        link(hello, File(probeDir, "hello"))
        link(File(nativeDir, "libprobeplugin.so"), File(probeDir, "plugin.so"))
        check("direct exec", listOf(hello.path), "hello-ok")
        check("exec via link in filesDir", listOf(File(probeDir, "hello").path), "hello-ok")
        check("nested exec (native execv of link)", listOf(hello.path, "exec", File(probeDir, "hello").path), "hello-ok")
        check("dlopen plugin in nativeLibraryDir", listOf(File(nativeDir, "libprobedl.so").path, File(nativeDir, "libprobeplugin.so").path), "answer=42")
        check("dlopen plugin via link", listOf(File(nativeDir, "libprobedl.so").path, File(probeDir, "plugin.so").path), "answer=42")

        if (!File(nativeDir, "libxtgcc.so").exists()) {
            say("[toolchain] not bundled in this APK; skipping")
            return
        }
        say("--- toolchain")
        setUpToolchainTree()
        check("gcc --version", listOf(File(tc, "bin/xtensa-esp-elf-gcc").path, "--version"), "14.2.0", toolchainEnv())
        check("as with esp32 dynconfig", listOf(File(tc, "xtensa-esp-elf/bin/as").path, "--version"), "GNU assembler", toolchainEnv())
        check("gcc finds cc1 (-print-prog-name)", gccArgs("-print-prog-name=cc1plus"), "cc1plus", toolchainEnv())
    }

    /** filesDir/tc: gcc-shaped tree of links to the lib*.so binaries (see spike log rules). */
    private fun setUpToolchainTree() {
        val v = "14.2.0"
        fun n(name: String) = File(nativeDir, name)
        link(n("libxtgcc.so"), File(tc, "bin/xtensa-esp-elf-gcc"))
        link(n("libxtgxx.so"), File(tc, "bin/xtensa-esp-elf-g++"))
        link(n("libxtar.so"), File(tc, "bin/xtensa-esp-elf-ar"))
        link(n("libxtobjcopy.so"), File(tc, "bin/xtensa-esp-elf-objcopy"))
        link(n("libxtsize.so"), File(tc, "bin/xtensa-esp-elf-size"))
        for (p in listOf("cc1", "cc1plus", "collect2")) link(n("libxt$p.so"), File(tc, "libexec/gcc/xtensa-esp-elf/$v/$p"))
        for (p in listOf("as", "ld", "ar")) link(n("libxt$p.so"), File(tc, "xtensa-esp-elf/bin/$p"))
        link(n("libxtensa_esp32.so"), File(tc, "lib/xtensa_esp32.so"))
    }

    private fun toolchainEnv() = mapOf(
        "GCC_EXEC_PREFIX" to "${tc.path}/lib/gcc/",
        "XTENSA_GNU_CONFIG" to "${tc.path}/lib/",
    )

    private fun gccArgs(vararg args: String) = listOf(
        File(tc, "bin/xtensa-esp-elf-gcc").path,
        "-B${tc.path}/libexec/gcc/xtensa-esp-elf/14.2.0/", "-B${tc.path}/xtensa-esp-elf/bin/",
        "--sysroot=${tc.path}/xtensa-esp-elf", "-mdynconfig=xtensa_esp32.so",
    ) + args

    /**
     * Replays a desktop Blink build (spike/make-probe-pack.py) from a pack pushed with
     * `adb push probe-pack /sdcard/Android/data/org.espsketchide.spike.execprobe/files/`.
     */
    private fun compileBlink() {
        val pushed = File(getExternalFilesDir(null), "probe-pack")
        val pack = File(filesDir, "pack")
        if (!File(pushed, "commands.jsonl").exists() && !File(pack, "commands.jsonl").exists()) {
            say("[blink] no pack: adb push probe-pack ${getExternalFilesDir(null)}/"); return
        }
        if (!File(nativeDir, "libxtgcc.so").exists()) { say("[blink] toolchain not bundled"); return }
        setUpToolchainTree()

        if (File(pushed, "commands.jsonl").exists()) {
            val t = System.nanoTime()
            pack.deleteRecursively()
            pushed.copyRecursively(pack, overwrite = true)
            pushed.deleteRecursively()
            say("[blink] copied pack to internal storage in ${ms(t)} ms")
        }
        val v = "14.2.0"
        link(File(pack, "tc-data/lib/gcc/xtensa-esp-elf/$v"), File(tc, "lib/gcc/xtensa-esp-elf/$v"))
        link(File(pack, "tc-data/xtensa-esp-elf/include"), File(tc, "xtensa-esp-elf/include"))
        link(File(pack, "tc-data/xtensa-esp-elf/lib"), File(tc, "xtensa-esp-elf/lib"))

        val build = File(filesDir, "build")
        build.deleteRecursively()
        File(pack, "build-seed").copyRecursively(build, overwrite = true)

        val lines = File(pack, "commands.jsonl").readLines().filter { it.isNotBlank() }
        say("--- P0.4 compile Blink: ${lines.size} commands")
        val memory = PeakMemory().also { it.start() }
        val t0 = System.nanoTime()
        var slowest = 0L to ""
        for ((i, line) in lines.withIndex()) {
            val argv = JSONArray(line).let { a -> List(a.length()) { a.getString(it) } }.map {
                it.replace("@TC@", tc.path).replace("@PACK@", pack.path).replace("@BUILD@", build.path)
            }
            // build-seed doesn't carry empty folders (e.g. core/), so create each output's folder.
            argv.indexOf("-o").takeIf { it >= 0 && it + 1 < argv.size }?.let { File(argv[it + 1]).parentFile?.mkdirs() }
            val t = System.nanoTime()
            val (code, text) = run(argv, toolchainEnv(), build)
            val took = ms(t)
            if (took > slowest.first) slowest = took to argv.last().substringAfterLast('/')
            if (code != 0) {
                say("[blink] FAIL at ${i + 1}/${lines.size} exit=$code: ${argv.take(1)}\n${text.take(1500)}")
                memory.finish(); return
            }
            if ((i + 1) % 10 == 0) say("  ${i + 1}/${lines.size} (${ms(t0) / 1000} s)")
        }
        memory.finish()
        val elf = File(build, "Blink.ino.elf")
        say("[blink] ${if (elf.exists()) "PASS" else "FAIL (no ELF)"}: total ${ms(t0) / 1000.0} s, " +
            "slowest step ${slowest.first} ms (${slowest.second}), peak toolchain RSS ${memory.peakKb / 1024} MB, " +
            "ELF ${elf.length()} bytes")
        if (elf.exists()) {
            val (_, size) = run(listOf(File(tc, "bin/xtensa-esp-elf-size").path, "-A", elf.path), toolchainEnv())
            say(size.lines().filter { it.startsWith(".flash") || it.startsWith(".iram0.text ") || it.startsWith(".dram0.data") }.joinToString("\n"))
        }
    }

    private fun ms(since: Long) = (System.nanoTime() - since) / 1_000_000

    private fun share() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, log.toString())
        }, "Share P0 results"))
    }

    /** Polls /proc for our uid's other processes (the toolchain) and records the largest RSS. */
    private class PeakMemory : Thread() {
        @Volatile var peakKb = 0L
        @Volatile private var running = true
        private val self = android.os.Process.myPid()
        private val uid = android.os.Process.myUid()

        override fun run() {
            while (running) {
                File("/proc").listFiles()?.forEach { dir ->
                    val pid = dir.name.toIntOrNull() ?: return@forEach
                    if (pid == self) return@forEach
                    val status = try { File(dir, "status").readLines() } catch (e: Exception) { return@forEach }
                    val owner = status.firstOrNull { it.startsWith("Uid:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toIntOrNull()
                    if (owner != uid) return@forEach
                    val rss = status.firstOrNull { it.startsWith("VmRSS:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull() ?: 0
                    if (rss > peakKb) peakKb = rss
                }
                sleep(50)
            }
        }

        fun finish() { running = false; join(1000) }
    }
}
