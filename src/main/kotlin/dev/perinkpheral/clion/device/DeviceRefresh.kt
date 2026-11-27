// SPDX-License-Identifier: MIT
package dev.perinkpheral.clion.device

import dev.perinkpheral.core.Json
import dev.perinkpheral.core.device.LogFollower
import dev.perinkpheral.core.device.Screen
import java.io.IOException
import java.awt.image.BufferedImage

internal class DeviceRefresh(status: () -> String, logs: () -> LogFollower.Part?, screen: () -> Screen) {
    val status = runCatching {
        Json.parse(status()) as? Map<*, *> ?: throw IOException("Invalid device status")
    }
    // A failed screen request must not hide status or logs.
    val logs = runCatching(logs)
    val screen = runCatching {
        if (this.status.isSuccess) screen() else null
    }
    // Failed status or screen requests leave the last displayed frame in place.
    val error: Throwable? get() = status.exceptionOrNull() ?: logs.exceptionOrNull() ?: screen.exceptionOrNull()

    fun updateScreen(show: (BufferedImage) -> Unit) {
        val frame = screen.getOrNull()
        // Frame numbers can repeat after restarting a simulator or device.
        if (frame != null) show(frame.image())
    }
}
