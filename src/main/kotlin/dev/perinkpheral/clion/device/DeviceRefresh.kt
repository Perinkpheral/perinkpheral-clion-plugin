// SPDX-License-Identifier: MIT
package dev.perinkpheral.clion.device

import dev.perinkpheral.core.Json
import dev.perinkpheral.core.device.LogFollower
import dev.perinkpheral.core.device.Screen
import java.io.IOException

internal class DeviceRefresh(status: () -> String, logs: () -> LogFollower.Part?, screen: () -> Screen,
                             isSimulator: Boolean = false, screenSupport: DeviceScreenSupport = DeviceScreenSupport()) {
    val status = runCatching {
        Json.parse(status()) as? Map<*, *> ?: throw IOException("Invalid device status")
    }
    // A missing development route must not hide status or logs.
    val logs = runCatching(logs)
    val screen = runCatching {
        this.status.getOrNull()?.let { screenSupport.read(it, isSimulator, screen) }
    }
    // Failed status or screen requests leave the last displayed frame in place.
    val clearScreen = this.status.isSuccess && this.screen.isSuccess && this.screen.getOrNull() == null
    val error: Throwable? get() = status.exceptionOrNull() ?: logs.exceptionOrNull() ?: screen.exceptionOrNull()
}

internal class DeviceScreenSupport {
    private var firmware: List<Any?>? = null
    private var absent = false

    fun read(status: Map<*, *>, isSimulator: Boolean, screen: () -> Screen): Screen? {
        val identity = listOf(status["firmware"], status["image_id"], status["built"])
        if (identity != firmware) {
            firmware = identity
            absent = false
        }
        if (isSimulator || status["dev_tools"] == true || status["firmware"] == "simulator") return screen()
        if (status["dev_tools"] == false || absent) return null
        // Older development builds omit the flag. Probe once per firmware; only a
        // missing route is conclusive, so a timeout or busy response remains retryable.
        return try {
            screen()
        } catch (error: IOException) {
            if (error.message?.startsWith("HTTP 404 from /api/dev/screen:") != true) throw error
            absent = true
            null
        }
    }
}
