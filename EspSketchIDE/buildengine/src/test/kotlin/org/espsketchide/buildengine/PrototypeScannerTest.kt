package org.espsketchide.buildengine

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PrototypeScannerTest {

    private fun functions(code: String) = PrototypeScanner.scan(code, defaultFile = "S.ino")

    private fun protos(code: String) = functions(code).filter { it.needsPrototype }.map { it.prototype }

    @Test
    fun findsTopLevelDefinitionsWithLines() {
        val found = functions(
            """
            const int LED = 2;
            void setup() {
              pinMode(LED, OUTPUT);
            }

            int add(int a, int b) { return a + b; }
            """.trimIndent()
        )

        assertThat(found.map { it.name }).containsExactly("setup", "add").inOrder()
        assertThat(found.map { it.line }).containsExactly(2, 6).inOrder()
        assertThat(found.map { it.prototype }).containsExactly("void setup();", "int add(int a, int b);").inOrder()
    }

    @Test
    fun multiLineSignaturesAreJoined() {
        assertThat(protos("unsigned long\nmeasure(int pin,\n        bool fast)\n{\n  return 0;\n}"))
            .containsExactly("unsigned long measure(int pin, bool fast);")
    }

    @Test
    fun ignoresBodiesControlFlowAndLambdas() {
        val code = """
            void loop() {
              if (x) { y(); }
              for (int i = 0; i < 3; i++) { z(i); }
              auto f = [](int a) { return a; };
            }
        """.trimIndent()

        assertThat(functions(code).map { it.name }).containsExactly("loop")
    }

    @Test
    fun skipsCommentsStringsAndCharLiterals() {
        val code = """
            // void fake1() {
            /* void fake2() { } */
            const char *s = "void fake3() {";
            char c = '{';
            const char *r = R"x(void fake4() { )x";
            void real() {}
        """.trimIndent()

        assertThat(functions(code).map { it.name }).containsExactly("real")
    }

    @Test
    fun structsClassesNamespacesAndExternBlocksAreNotFunctions() {
        val code = """
            struct Point { int x; int y; };
            class Led { public: void on() {} };
            namespace util { int twice(int v) { return v * 2; } }
            enum Mode { A, B };
            extern "C" { void c_api(void); }
            void top(Point p) {}
        """.trimIndent()

        assertThat(functions(code).map { it.name }).containsExactly("top")
    }

    @Test
    fun structParamsAndPointersAndReferences() {
        assertThat(protos("static inline const char *name(struct Thing &t, int *out) { return 0; }"))
            .containsExactly("static inline const char *name(struct Thing &t, int *out);")
    }

    @Test
    fun alreadyDeclaredFunctionsGetNoPrototype() {
        val code = """
            void helper(int);
            void setup() { helper(1); }
            void helper(int v) {}
        """.trimIndent()

        assertThat(protos(code)).containsExactly("void setup();")
    }

    @Test
    fun templatesMembersAndOperatorsAreSkipped() {
        val code = """
            template <typename T> T biggest(T a, T b) { return a > b ? a : b; }
            void Led::off() {}
            bool operator==(const Point &a, const Point &b) { return true; }
            void ok() {}
        """.trimIndent()

        assertThat(protos(code)).containsExactly("void ok();")
    }

    @Test
    fun defaultArgumentsAreDroppedFromPrototypes() {
        assertThat(protos("void blink(int times = 3, const char *msg = \"a,b\") {}"))
            .containsExactly("void blink(int times, const char *msg);")
    }

    @Test
    fun attributesAndTrailingQualifiersAreKept() {
        assertThat(protos("void __attribute__((section(\".iram1\"))) onTimer() noexcept { }"))
            .containsExactly("void __attribute__((section(\".iram1\"))) onTimer() noexcept;")
    }

    @Test
    fun followsGccLineMarkers() {
        val code = """
            # 1 "/s/Blink.ino"
            int a;
            # 1 "/usr/include/Arduino.h" 1 3
            void lib_func() {}
            # 5 "/s/Blink.ino" 2
            void setup() {}
            # 1 "/s/Other.ino"

            void other() {}
        """.trimIndent()

        val found = PrototypeScanner.scan(code, defaultFile = "?")
        assertThat(found.map { Triple(it.name, it.file, it.line) }).containsExactly(
            Triple("lib_func", "/usr/include/Arduino.h", 1),
            Triple("setup", "/s/Blink.ino", 5),
            Triple("other", "/s/Other.ino", 2),
        ).inOrder()
    }

    @Test
    fun functionPointerVariablesAreNotFunctions() {
        val code = """
            void (*handler)(int) = nullptr;
            int (*table[2])(void);
            void real() {}
        """.trimIndent()

        assertThat(functions(code).map { it.name }).containsExactly("real")
    }
}
