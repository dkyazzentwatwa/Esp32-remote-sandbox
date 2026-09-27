package org.espsketchide.buildengine

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SketchPreprocessorTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sketch(name: String, vararg files: Pair<String, String>) =
        tmp.newFolder(name).also { dir -> files.forEach { (n, text) -> dir.resolve(n).writeText(text) } }

    @Test
    fun mergesPrimaryFirstThenOthersAlphabetically() {
        val dir = sketch("Main", "b.ino" to "int b;", "Main.ino" to "int m;", "a.ino" to "int a;", "x.cpp" to "int x;")

        val merged = SketchPreprocessor.merge(SketchPreprocessor.inoFiles(dir))

        assertThat(merged).isEqualTo(
            "#include <Arduino.h>\n" +
                "#line 1 \"${dir.resolve("Main.ino")}\"\nint m;\n" +
                "#line 1 \"${dir.resolve("a.ino")}\"\nint a;\n" +
                "#line 1 \"${dir.resolve("b.ino")}\"\nint b;\n"
        )
    }

    @Test
    fun insertsPrototypesBeforeTheFirstFunction() {
        val dir = sketch("S", "S.ino" to "const int P = 2;\n\nvoid setup() { go(); }\nvoid go() {}\n")
        val ino = dir.resolve("S.ino").path
        val merged = SketchPreprocessor.merge(SketchPreprocessor.inoFiles(dir))

        // Without a real preprocessor the merged text itself stands in for gcc -E output.
        val result = SketchPreprocessor.addPrototypes(merged, merged, setOf(ino))

        assertThat(result.lines()).containsExactly(
            "#include <Arduino.h>",
            "#line 1 \"$ino\"",
            "const int P = 2;",
            "",
            "#line 3 \"$ino\"",
            "void setup();",
            "#line 4 \"$ino\"",
            "void go();",
            "#line 3 \"$ino\"",
            "void setup() { go(); }",
            "void go() {}",
            "",
            "",
        ).inOrder()
    }

    @Test
    fun functionsFromHeadersAreNotPrototyped() {
        val dir = sketch("S", "S.ino" to "void setup() {}\n")
        val ino = dir.resolve("S.ino").path
        val merged = SketchPreprocessor.merge(SketchPreprocessor.inoFiles(dir))
        // Arduino.h declares setup(); that must not suppress the sketch's prototype.
        val preprocessed = "# 1 \"/core/Arduino.h\"\nvoid setup(void);\nvoid pinMode(int, int) {}\n# 1 \"$ino\"\nvoid setup() {}\n"

        val result = SketchPreprocessor.addPrototypes(merged, preprocessed, setOf(ino))

        assertThat(result).doesNotContain("pinMode")
        assertThat(result).contains("void setup();")
    }

    @Test
    fun sketchWithoutFunctionsIsUnchanged() {
        val dir = sketch("S", "S.ino" to "int x;\n")
        val merged = SketchPreprocessor.merge(SketchPreprocessor.inoFiles(dir))

        assertThat(SketchPreprocessor.addPrototypes(merged, merged, setOf(dir.resolve("S.ino").path))).isEqualTo(merged)
    }

    @Test
    fun commandLineSplitFollowsArduinoQuoting() {
        // Leading quotes group and are removed; inner quotes are kept (string macros).
        assertThat(CommandLine.split("\"/bin/g++\"  -DBOARD=\"ESP32_DEV\" \"-I/a b\" -c 'x y.cpp'"))
            .containsExactly("/bin/g++", "-DBOARD=\"ESP32_DEV\"", "-I/a b", "-c", "x y.cpp").inOrder()
    }
}
