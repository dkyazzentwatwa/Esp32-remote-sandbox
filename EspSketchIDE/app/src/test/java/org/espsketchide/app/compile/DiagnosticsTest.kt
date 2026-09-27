package org.espsketchide.app.compile

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

class DiagnosticsTest {

    private val sketch = File("/data/cache/stage/Blink")

    @Test
    fun parsesGccMessagesAndMapsSketchPaths() {
        val output = """
            /data/cache/stage/Blink/Blink.ino: In function 'void loop()':
            /data/cache/stage/Blink/Blink.ino:7:3: error: 'digitalWrit' was not declared in this scope; did you mean 'digitalWrite'?
                7 |   digitalWrit(LED, HIGH);
            /data/cache/stage/Blink/src/a.cpp:2:10: warning: unused variable 'x' [-Wunused-variable]
            /data/packs/esp32/cores/esp32/Arduino.h:120:6: note: 'digitalWrite' declared here
            /data/cache/stage/Blink/Blink.ino:3:10: fatal error: Foo.h: No such file or directory
        """.trimIndent()

        val d = Diagnostics.parse(output, sketch)

        assertThat(d.map { "${it.severity} ${it.file}:${it.line}:${it.column} ${it.inSketch}" }).containsExactly(
            "ERROR Blink.ino:7:3 true",
            "WARNING src/a.cpp:2:10 true",
            "NOTE /data/packs/esp32/cores/esp32/Arduino.h:120:6 false",
            "ERROR Blink.ino:3:10 true",
        ).inOrder()
        assertThat(d[0].message).isEqualTo("'digitalWrit' was not declared in this scope; did you mean 'digitalWrite'?")
    }

    @Test
    fun ignoresOtherLinesAndDuplicates() {
        val line = "/data/cache/stage/Blink/Blink.ino:1:1: error: x"

        assertThat(Diagnostics.parse("collect2: error: ld returned 1 exit status\n$line\n$line", sketch)).hasSize(1)
    }
}
