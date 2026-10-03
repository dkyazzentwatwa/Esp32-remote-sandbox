package org.espsketchide.app.reference

import com.google.common.truth.Truth.assertThat
import org.espsketchide.app.settings.Board
import org.junit.Test
import java.io.File

class PinGuideTest {

    private val guide = PinGuide.parse(File("src/main/assets/reference/pins.json").readText())

    @Test
    fun everyBoardFamilyHasAGuide() {
        Board.entries.forEach { board ->
            val forBoard = guide.forBoard(board)!!
            assertThat(forBoard.pins).isNotEmpty()
            assertThat(forBoard.notes).isNotEmpty()
        }
    }

    @Test
    fun flashPinsAreMarkedAvoid() {
        assertThat(guide.forBoard(Board.ESP32)!!.pins.first { it.name == "GPIO 6–11" }.level).isEqualTo(PinLevel.AVOID)
        assertThat(guide.forBoard(Board.ESP32)!!.pins.first { it.name == "GPIO 4" }.level).isEqualTo(PinLevel.SAFE)
    }

    @Test
    fun unknownLevelIsRejected() {
        val bad = """{"boards":[{"board":"esp32","title":"t","notes":[],"pins":[{"name":"x","level":"maybe","features":"","note":""}]}]}"""
        val failure = runCatching { PinGuide.parse(bad) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(IllegalArgumentException::class.java)
    }
}
