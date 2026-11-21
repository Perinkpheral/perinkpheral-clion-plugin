// SPDX-License-Identifier: MIT
package dev.perinkpheral.clion.device

import dev.perinkpheral.core.Json
import dev.perinkpheral.core.device.LogFollower
import dev.perinkpheral.core.device.Screen
import java.io.IOException

internal class DeviceRefresh(status: () -> String, logs: () -> LogFollower.Part?, screen: () -> Screen,
                             isSimulator: Boolean = false) {
    val status = runCatching {
        Json.parse(status()) as? Map<*, *> ?: throw IOException("Invalid device status")
    }
    // A missing development route must not hide status or logs.
    val logs = runCatching(logs)
    // The bundled simulator has a screen route but predates the capability flag.
    val supportsScreen = isSimulator || this.status.getOrNull()?.get("dev_tools") == true
    val screen = runCatching { if (supportsScreen) screen() else null }
    val error: Throwable? get() = status.exceptionOrNull() ?: logs.exceptionOrNull() ?: screen.exceptionOrNull()
}
