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
import kotlin.test.assertTrue

class DeviceRefreshTest {
    @Test
    fun restartedSimulatorReplacesPixelsEvenWhenTheFrameNumberRepeats() {
        val support = DeviceScreenSupport()
        var shown: BufferedImage? = null
        val oldFrame = Screen(listOf(booleanArrayOf(true)), 1, false)
        val newFrame = Screen(listOf(booleanArrayOf(false)), 1, false)
        fun refresh(frame: Screen) = DeviceRefresh({ "{\"firmware\":\"simulator\"}" },
            { LogFollower.Part("", true) }, { frame }, isSimulator = true, screenSupport = support)
        refresh(oldFrame).updateScreen { shown = it }
        assertEquals(oldFrame.image().getRGB(0, 0), shown!!.getRGB(0, 0))
        refresh(newFrame).updateScreen { shown = it }
        assertEquals(newFrame.image().getRGB(0, 0), shown!!.getRGB(0, 0))
    }

    @Test
    fun displayKeepsLastImageOnFailureAndClearsItWhenScreenIsUnsupported() {
        val previous = BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB)
        var shown: BufferedImage? = previous
        val failed = DeviceRefresh({ "{\"dev_tools\":true}" }, { null }, { throw IOException("timeout") })
        failed.updateScreen { shown = it }
        assertSame(previous, shown)
        val release = DeviceRefresh({ "{\"dev_tools\":false}" }, { null }, { error("No screen route") })
        release.updateScreen { shown = it }
        assertNull(shown)
    }

    @Test
    fun releaseFirmwareReadsStatusAndLogsWithoutScreen() {
        for (capability in listOf("", ",\"dev_tools\":false")) {
            val refreshed = DeviceRefresh({ "{\"firmware\":\"0.1.80\"$capability}" },
                { LogFollower.Part("release log", false) }, { throw IOException("HTTP 404 from /api/dev/screen: not found") })
            assertEquals("0.1.80", refreshed.status.getOrThrow()["firmware"])
            assertEquals("release log", refreshed.logs.getOrThrow()?.text)
            assertNull(refreshed.screen.getOrThrow())
            assertNull(refreshed.error)
            assertTrue(refreshed.clearScreen)
        }
    }

    @Test
    fun developmentFirmwareAlsoReadsScreen() {
        val frame = Screen(listOf(booleanArrayOf(true)), 12, true)
        val refreshed = DeviceRefresh({ "{\"dev_tools\":true}" }, { LogFollower.Part("log", false) }, { frame })
        assertSame(frame, refreshed.screen.getOrThrow())
        assertNull(refreshed.error)
        assertFalse(refreshed.clearScreen)
    }

    @Test
    fun ownedSimulatorReadsScreenWithoutCapabilityFlag() {
        val frame = Screen(listOf(booleanArrayOf(true)), 2, false)
        val refreshed = DeviceRefresh({ "{\"firmware\":\"simulator\"}" }, { null }, { frame }, isSimulator = true)
        assertSame(frame, refreshed.screen.getOrThrow())
        assertNull(refreshed.error)
    }

    @Test
    fun screenFailureDoesNotHideStatusOrLogs() {
        val refreshed = DeviceRefresh({ "{\"firmware\":\"test\",\"dev_tools\":true}" },
            { LogFollower.Part("still logged", true) }, { throw IOException("screen busy") })
        assertEquals("test", refreshed.status.getOrThrow()["firmware"])
        assertEquals("still logged", refreshed.logs.getOrThrow()?.text)
        assertEquals("screen busy", refreshed.error?.message)
        assertFalse(refreshed.clearScreen)
    }

    @Test
    fun statusFailureStillReadsLogsAndSkipsScreen() {
        val refreshed = DeviceRefresh({ throw IOException("status unavailable") },
            { LogFollower.Part("log", false) }, { error("Screen capability is unknown") })
        assertFalse(refreshed.status.isSuccess)
        assertNotNull(refreshed.logs.getOrThrow())
        assertNull(refreshed.screen.getOrThrow())
        assertFalse(refreshed.clearScreen)
    }

    @Test
    fun developmentFirmwareAndExternalSimulatorReadScreenWithoutFlag() {
        val frame = Screen(listOf(booleanArrayOf(true)), 3, false)
        for (firmware in listOf("0.1.50-debug", "0.1.50-local", "simulator")) {
            val refreshed = DeviceRefresh({ "{\"firmware\":\"$firmware\"}" }, { null }, { frame })
            assertSame(frame, refreshed.screen.getOrThrow())
            assertFalse(refreshed.clearScreen)
            assertNull(refreshed.error)
        }
    }

    @Test
    fun missingLegacyRouteIsProbedOnceAndRecheckedAfterFirmwareChange() {
        val support = DeviceScreenSupport()
        var probes = 0
        repeat(3) {
            val refreshed = DeviceRefresh({ "{\"firmware\":\"0.1.50\",\"image_id\":\"release\"}" }, { null }, {
                probes++
                throw IOException("HTTP 404 from /api/dev/screen: not found")
            }, screenSupport = support)
            assertTrue(refreshed.clearScreen)
            assertNull(refreshed.error)
        }
        assertEquals(1, probes)
        val frame = Screen(listOf(booleanArrayOf(false)), 1, false)
        val changed = DeviceRefresh({ "{\"firmware\":\"0.1.50\",\"image_id\":\"development\"}" }, { null }, {
            probes++
            frame
        }, screenSupport = support)
        assertSame(frame, changed.screen.getOrThrow())
        assertEquals(2, probes)
    }

    @Test
    fun legacyScreenTimeoutIsRetriedAndStatusFailureKeepsLastFrame() {
        val support = DeviceScreenSupport()
        val frame = Screen(listOf(booleanArrayOf(false)), 7, false)
        val failed = DeviceRefresh({ "{}" }, { null }, { throw IOException("timeout") }, screenSupport = support)
        assertFalse(failed.clearScreen)
        assertEquals("timeout", failed.error?.message)
        val success = DeviceRefresh({ "{}" }, { null }, { frame }, screenSupport = support)
        assertSame(frame, success.screen.getOrThrow())
        val failedStatus = DeviceRefresh({ throw IOException("timeout") }, { null }, { error("No status") },
            screenSupport = support)
        // This is the exact clear decision used by the tool window for its displayed frame.
        var shown: Screen? = success.screen.getOrThrow()
        if (failedStatus.clearScreen) shown = null
        assertSame(frame, shown)
        val release = DeviceRefresh({ "{\"dev_tools\":false}" }, { null }, { error("Explicitly disabled") },
            screenSupport = support)
        if (release.clearScreen) shown = null
        assertNull(shown)
    }

    @Test
    fun logFailureStillReadsStatusAndScreen() {
        val frame = Screen(listOf(booleanArrayOf(false)), 1, false)
        val refreshed = DeviceRefresh({ "{\"dev_tools\":true}" }, { throw IOException("log failed") }, { frame })
        assertSame(frame, refreshed.screen.getOrThrow())
        assertEquals("log failed", refreshed.error?.message)
    }
}
