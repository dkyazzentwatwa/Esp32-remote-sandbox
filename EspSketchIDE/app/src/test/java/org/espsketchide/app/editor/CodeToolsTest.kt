package org.espsketchide.app.editor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CodeToolsTest {

    @Test
    fun commentAddsSlashesAtTheCommonIndent() {
        val lines = listOf("  digitalWrite(2, HIGH);", "", "    delay(500);")
        assertThat(CodeTools.toggleComment(lines)).containsExactly(
            "  // digitalWrite(2, HIGH);", "", "  //   delay(500);"
        ).inOrder()
    }

    @Test
    fun uncommentWhenEveryNonBlankLineIsCommented() {
        val lines = listOf("  // digitalWrite(2, HIGH);", "  //delay(500);")
        assertThat(CodeTools.toggleComment(lines)).containsExactly("  digitalWrite(2, HIGH);", "  delay(500);").inOrder()
    }

    @Test
    fun mixedLinesGetCommented() {
        assertThat(CodeTools.toggleComment(listOf("// a();", "b();"))).containsExactly("// // a();", "// b();").inOrder()
    }

    @Test
    fun formatReindentsByBraceDepth() {
        val messy = """
            void setup() {
            Serial.begin(115200);
                  if (x) {
            y();
               }
            }
        """.trimIndent()
        assertThat(CodeTools.format(messy)).isEqualTo(
            """
            void setup() {
              Serial.begin(115200);
              if (x) {
                y();
              }
            }
            """.trimIndent()
        )
    }

    @Test
    fun formatIgnoresBracesInStringsCharsAndComments() {
        val code = "void loop() {\nSerial.println(\"{ not a block\"); // {\nchar c = '{';\n/* { */\n}"
        assertThat(CodeTools.format(code)).isEqualTo(
            "void loop() {\n  Serial.println(\"{ not a block\"); // {\n  char c = '{';\n  /* { */\n}"
        )
    }

    @Test
    fun formatKeepsBlockCommentBodiesAndPreprocessorLines() {
        val code = "#define LED 2\n/*\n   keep me\n*/\nvoid f() {\n#ifdef X\ng();\n#endif\n}"
        assertThat(CodeTools.format(code)).isEqualTo(
            "#define LED 2\n/*\n   keep me\n*/\nvoid f() {\n#ifdef X\n  g();\n#endif\n}"
        )
    }

    @Test
    fun formatHandlesCloseThenOpenOnOneLineAndTrailingWhitespace() {
        assertThat(CodeTools.format("if (a) {\nb();\n} else {   \nc();\n}")).isEqualTo("if (a) {\n  b();\n} else {\n  c();\n}")
    }

    @Test
    fun formatNeverIndentsBelowZero() {
        assertThat(CodeTools.format("}\n}\nx();")).isEqualTo("}\n}\nx();")
    }
}
