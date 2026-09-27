package org.espsketchide.buildengine

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class BoardsTest {

    private val catalog = BoardCatalog.parse(
        Properties.parse(
            """
            menu.Partition=Partition Scheme
            menu.Speed=Upload Speed
            menu.Unused=Not used by any board

            dev.name=Dev Board
            dev.build.core=mycore
            dev.build.partitions=fallback
            dev.menu.Partition.small=Small app
            dev.menu.Partition.small.build.partitions=small
            dev.menu.Partition.big=Big app
            dev.menu.Partition.big.build.partitions=big
            dev.menu.Partition.big.upload.maximum_size=3145728
            dev.menu.Speed.fast=921600
            dev.menu.Speed.fast.upload.speed=921600
            dev.menu.Speed.slow=115200
            dev.menu.Speed.slow.upload.speed=115200

            mini.name=Mini
            mini.build.core=mycore
            """.trimIndent()
        )
    )

    @Test
    fun listsBoardsAndTheirMenusInFileOrder() {
        val dev = catalog["dev"]

        assertThat(catalog.boards.map { it.id }).containsExactly("dev", "mini")
        assertThat(dev.name).isEqualTo("Dev Board")
        assertThat(dev.menus.map { it.id }).containsExactly("Partition", "Speed").inOrder()
        assertThat(dev.menus[0].options.map { it.id }).containsExactly("small", "big").inOrder()
        assertThat(dev.menus[0].label).isEqualTo("Partition Scheme")
        assertThat(catalog["mini"].menus).isEmpty()
    }

    @Test
    fun firstOptionOfEachMenuIsTheDefault() {
        val props = catalog["dev"].resolve()

        assertThat(props["build.partitions"]).isEqualTo("small")
        assertThat(props["upload.speed"]).isEqualTo("921600")
        assertThat(props["_id"]).isEqualTo("dev")
    }

    @Test
    fun selectedOptionsOverrideBoardKeys() {
        val props = catalog["dev"].resolve(mapOf("Partition" to "big", "Speed" to "slow"))

        assertThat(props["build.partitions"]).isEqualTo("big")
        assertThat(props["upload.maximum_size"]).isEqualTo("3145728")
        assertThat(props["upload.speed"]).isEqualTo("115200")
    }

    @Test
    fun unknownBoardMenuOrOptionIsAnError() {
        assertThrows(BuildException::class.java) { catalog["nope"] }
        assertThrows(BuildException::class.java) { catalog["dev"].resolve(mapOf("Flash" to "qio")) }
        assertThrows(BuildException::class.java) { catalog["dev"].resolve(mapOf("Partition" to "medium")) }
    }
}
