package org.espsketchide.app.editor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SourceKindTest {

    @Test
    fun sketchSourcesUseCpp() {
        listOf("Blink.ino", "pins.h", "util.HPP", "a.c", "b.cpp", "c.cc").forEach {
            assertThat(sourceKindFor(it)).isEqualTo(SourceKind.CPP)
        }
    }

    @Test
    fun otherFilesArePlain() {
        listOf("notes.txt", "README", "data.csv").forEach {
            assertThat(sourceKindFor(it)).isEqualTo(SourceKind.PLAIN)
        }
    }
}
