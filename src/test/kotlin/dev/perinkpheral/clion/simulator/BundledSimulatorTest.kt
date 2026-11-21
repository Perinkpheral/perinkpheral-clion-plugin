// SPDX-License-Identifier: MIT
package dev.perinkpheral.clion.simulator

import dev.perinkpheral.core.contract.Contract
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BundledSimulatorTest {
    @Test
    fun everyBundleContainsTheCurrentSdkImports() {
        val sdk = File(System.getProperty("perinkpheral.sdk"))
        val imports = Contract.of(sdk).calls.map { it.name }
        for (platform in listOf("macos", "linux-x86_64", "linux-aarch64")) {
            val resource = assertNotNull(javaClass.getResourceAsStream("/simulator/$platform/perinkpheral-sim"))
            val binary = resource.use { it.readBytes() }.toString(Charsets.ISO_8859_1)
            // Whole strings avoid accepting gfx_text_width as the old gfx_text import.
            for (name in imports) assertTrue(binary.contains("\u0000$name\u0000"), "$platform lacks $name")
        }
    }
}
