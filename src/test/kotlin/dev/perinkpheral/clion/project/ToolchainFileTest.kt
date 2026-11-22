// SPDX-License-Identifier: MIT
package dev.perinkpheral.clion.project

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class ToolchainFileTest {
    @Test
    fun clearingAddressStillOverwritesTheEnvironmentAndPersistentCache() {
        for (host in listOf("previous-board.local", "")) {
            val text = ToolchainFile.content("", "", host, File("generated"))
            assertEquals(listOf(
                "set(ENV{PERINKPHERAL_HOST} \"$host\")",
                "set(PERINKPHERAL_HOST \"$host\" CACHE STRING \"The device's address\" FORCE)"
            ), text.lineSequence().filter { "PERINKPHERAL_HOST" in it }.toList())
        }
    }
}
