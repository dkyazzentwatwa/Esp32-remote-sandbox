package org.espsketchide.buildengine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PropertiesTest {

    @Test
    fun parsesKeyValueLinesAndSkipsComments() {
        val p = Properties.parse(
            """
            # comment
            name=My Core
              version = 1.2.3

            compiler.path={runtime.tools.gcc.path}/bin/
            empty=
            weird=a=b=c
            """.trimIndent()
        )

        assertThat(p["name"]).isEqualTo("My Core")
        assertThat(p["version"]).isEqualTo("1.2.3")
        assertThat(p["compiler.path"]).isEqualTo("{runtime.tools.gcc.path}/bin/")
        assertThat(p["empty"]).isEqualTo("")
        assertThat(p["weird"]).isEqualTo("a=b=c")
        assertThat(p.keys).doesNotContain("# comment")
    }

    @Test
    fun linuxSuffixOverridesBaseKeyRegardlessOfOrder() {
        val p = Properties.parse(
            """
            tool.cmd.linux=gcc-linux
            tool.cmd=gcc
            tool.cmd.windows=gcc.exe
            other.macosx=mac
            """.trimIndent()
        )

        assertThat(p["tool.cmd"]).isEqualTo("gcc-linux")
        assertThat(p["tool.cmd.windows"]).isEqualTo("gcc.exe") // other OSes stay as plain keys
        assertThat(p["other"]).isNull()
    }

    @Test
    fun expandsNestedPlaceholdersInnermostFirst() {
        val p = Properties.of(
            "build.chip_variant" to "esp32",
            "runtime.tools.esp32-libs.path" to "/tools/libs",
            "tools.libs.path" to "{runtime.tools.{build.chip_variant}-libs.path}",
            "compiler.sdk.path" to "{tools.libs.path}",
        )

        assertThat(p.expand("{compiler.sdk.path}/include")).isEqualTo("/tools/libs/include")
    }

    @Test
    fun leavesUnknownPlaceholdersForLaterSubstitution() {
        val p = Properties.of("a" to "x")

        assertThat(p.expand("{a} {source_file} -o {object_file}")).isEqualTo("x {source_file} -o {object_file}")
    }

    @Test
    fun stopsOnSelfReferenceInsteadOfLooping() {
        val p = Properties.of("a" to "{a}+", "b" to "{b}")

        assertThat(p.expand("{b}")).isEqualTo("{b}")
        assertThat(p.expand("{a}").length).isLessThan(100)
    }

    @Test
    fun subtreeStripsPrefix() {
        val p = Properties.of("esp32.name" to "ESP32 Dev", "esp32.build.mcu" to "esp32", "uno.name" to "Uno")

        assertThat(p.subtree("esp32").toMap()).containsExactly("name", "ESP32 Dev", "build.mcu", "esp32")
    }

    @Test
    fun laterMergeWins() {
        val merged = Properties.of("a" to "1", "b" to "1") + Properties.of("b" to "2")

        assertThat(merged.toMap()).containsExactly("a", "1", "b", "2")
    }
}
