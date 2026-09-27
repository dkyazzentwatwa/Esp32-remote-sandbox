package org.espsketchide.app.editor

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FontSizeTest {

    @Test
    fun stepsWithinRange() {
        assertThat(FontSize.larger(14f)).isEqualTo(16f)
        assertThat(FontSize.smaller(14f)).isEqualTo(12f)
    }

    @Test
    fun clampsAtBothEnds() {
        assertThat(FontSize.larger(FontSize.MAX)).isEqualTo(FontSize.MAX)
        assertThat(FontSize.smaller(FontSize.MIN)).isEqualTo(FontSize.MIN)
        assertThat(FontSize.clamp(100f)).isEqualTo(FontSize.MAX)
        assertThat(FontSize.clamp(1f)).isEqualTo(FontSize.MIN)
    }
}
