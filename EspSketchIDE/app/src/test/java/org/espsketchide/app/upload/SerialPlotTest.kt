package org.espsketchide.app.upload

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SerialPlotTest {

    @Test
    fun plainNumbersSeparatedBySpacesCommasOrTabs() {
        assertThat(SerialPlot.parseLine("12")).containsExactly(null to 12f)
        assertThat(SerialPlot.parseLine("1, 2.5\t-3")).containsExactly(null to 1f, null to 2.5f, null to -3f).inOrder()
    }

    @Test
    fun labelledValues() {
        assertThat(SerialPlot.parseLine("temp:21.5 humidity:40")).containsExactly("temp" to 21.5f, "humidity" to 40f).inOrder()
        assertThat(SerialPlot.parseLine("temp: 21.5, hum: 40")).containsExactly("temp" to 21.5f, "hum" to 40f).inOrder()
    }

    @Test
    fun textLinesAreIgnored() {
        assertThat(SerialPlot.parseLine("Connecting to WiFi...")).isEmpty()
        assertThat(SerialPlot.parseLine("")).isEmpty()
    }

    @Test
    fun seriesFromTextUseLabelsOrPositions() {
        val text = "a:1 b:10\na:2 b:20\nhello\na:3 b:30\n"
        val series = SerialPlot.series(text, maxPoints = 100)
        assertThat(series.keys).containsExactly("a", "b").inOrder()
        assertThat(series.getValue("a").toList()).containsExactly(1f, 2f, 3f).inOrder()
        assertThat(series.getValue("b").toList()).containsExactly(10f, 20f, 30f).inOrder()

        val unlabelled = SerialPlot.series("5 50\n6 60\n", maxPoints = 100)
        assertThat(unlabelled.keys).containsExactly("1", "2").inOrder()
    }

    @Test
    fun partialLastLineIsSkippedAndHistoryIsCapped() {
        val text = (1..10).joinToString("\n") { "$it" } + "\n1"
        val series = SerialPlot.series(text, maxPoints = 4)
        assertThat(series.getValue("1").toList()).containsExactly(7f, 8f, 9f, 10f).inOrder()
    }
}
