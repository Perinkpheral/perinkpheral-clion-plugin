// SPDX-License-Identifier: MIT
package dev.perinkpheral.clion.device

import dev.perinkpheral.core.device.LogFollower
import dev.perinkpheral.core.device.Screen
import java.io.IOException
import java.awt.image.BufferedImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class DeviceRefreshTest {
    @Test
    fun restartedSimulatorReplacesPixelsEvenWhenTheFrameNumberRepeats() {
        var shown: BufferedImage? = null
        val oldFrame = Screen(listOf(booleanArrayOf(true)), 1, false)
        val newFrame = Screen(listOf(booleanArrayOf(false)), 1, false)
        fun refresh(frame: Screen) = DeviceRefresh({ "{\"firmware\":\"simulator\"}" },
            { LogFollower.Part("", true) }, { frame })
        refresh(oldFrame).updateScreen { shown = it }
        assertEquals(oldFrame.image().getRGB(0, 0), shown!!.getRGB(0, 0))
        refresh(newFrame).updateScreen { shown = it }
        assertEquals(newFrame.image().getRGB(0, 0), shown!!.getRGB(0, 0))
    }

    @Test
    fun displayKeepsLastImageOnScreenOrStatusFailure() {
        val previous = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        var shown: BufferedImage? = previous
        val failedScreen = DeviceRefresh({ "{\"dev_tools\":false}" }, { null }, { throw IOException("timeout") })
        failedScreen.updateScreen { shown = it }
        assertSame(previous, shown)
        val failedStatus = DeviceRefresh({ throw IOException("status unavailable") }, { null }, { error("No status") })
        failedStatus.updateScreen { shown = it }
        assertSame(previous, shown)
    }

    @Test
    fun releaseAndDevelopmentFirmwareReadStatusLogsAndScreen() {
        val frame = Screen(listOf(booleanArrayOf(true)), 12, true)
        for (capability in listOf("", ",\"dev_tools\":false", ",\"dev_tools\":true")) {
            val refreshed = DeviceRefresh({ "{\"firmware\":\"test\"$capability}" },
                { LogFollower.Part("log", false) }, { frame })
            assertEquals("test", refreshed.status.getOrThrow()["firmware"])
            assertEquals("log", refreshed.logs.getOrThrow()?.text)
            assertSame(frame, refreshed.screen.getOrThrow())
            assertNull(refreshed.error)
        }
    }

    @Test
    fun screenFailureDoesNotHideStatusOrLogs() {
        val refreshed = DeviceRefresh({ "{\"firmware\":\"test\",\"dev_tools\":false}" },
            { LogFollower.Part("still logged", true) }, { throw IOException("screen busy") })
        assertEquals("test", refreshed.status.getOrThrow()["firmware"])
        assertEquals("still logged", refreshed.logs.getOrThrow()?.text)
        assertEquals("screen busy", refreshed.error?.message)
    }

    @Test
    fun statusFailureStillReadsLogsAndSkipsScreen() {
        val refreshed = DeviceRefresh({ throw IOException("status unavailable") },
            { LogFollower.Part("log", false) }, { error("No status") })
        assertFalse(refreshed.status.isSuccess)
        assertNotNull(refreshed.logs.getOrThrow())
        assertNull(refreshed.screen.getOrThrow())
    }

    @Test
    fun logFailureStillReadsStatusAndScreen() {
        val frame = Screen(listOf(booleanArrayOf(false)), 1, false)
        val refreshed = DeviceRefresh({ "{\"dev_tools\":false}" }, { throw IOException("log failed") }, { frame })
        assertSame(frame, refreshed.screen.getOrThrow())
        assertEquals("log failed", refreshed.error?.message)
    }
}
