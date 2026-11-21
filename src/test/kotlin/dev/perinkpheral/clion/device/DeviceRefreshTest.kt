// SPDX-License-Identifier: MIT
package dev.perinkpheral.clion.device

import dev.perinkpheral.core.device.LogFollower
import dev.perinkpheral.core.device.Screen
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class DeviceRefreshTest {
    @Test
    fun releaseFirmwareReadsStatusAndLogsWithoutScreen() {
        for (capability in listOf("", ",\"dev_tools\":false")) {
            val refreshed = DeviceRefresh({ "{\"firmware\":\"0.1.80\"$capability}" },
                { LogFollower.Part("release log", false) }, { error("Release has no screen route") })
            assertEquals("0.1.80", refreshed.status.getOrThrow()["firmware"])
            assertEquals("release log", refreshed.logs.getOrThrow()?.text)
            assertNull(refreshed.screen.getOrThrow())
            assertNull(refreshed.error)
        }
    }

    @Test
    fun developmentFirmwareAlsoReadsScreen() {
        val frame = Screen(listOf(booleanArrayOf(true)), 12, true)
        val refreshed = DeviceRefresh({ "{\"dev_tools\":true}" }, { LogFollower.Part("log", false) }, { frame })
        assertSame(frame, refreshed.screen.getOrThrow())
        assertNull(refreshed.error)
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
    }

    @Test
    fun statusFailureStillReadsLogsAndSkipsScreen() {
        val refreshed = DeviceRefresh({ throw IOException("status unavailable") },
            { LogFollower.Part("log", false) }, { error("Screen capability is unknown") })
        assertFalse(refreshed.status.isSuccess)
        assertNotNull(refreshed.logs.getOrThrow())
        assertNull(refreshed.screen.getOrThrow())
    }

    @Test
    fun logFailureStillReadsStatusAndScreen() {
        val frame = Screen(listOf(booleanArrayOf(false)), 1, false)
        val refreshed = DeviceRefresh({ "{\"dev_tools\":true}" }, { throw IOException("log failed") }, { frame })
        assertSame(frame, refreshed.screen.getOrThrow())
        assertEquals("log failed", refreshed.error?.message)
    }
}
