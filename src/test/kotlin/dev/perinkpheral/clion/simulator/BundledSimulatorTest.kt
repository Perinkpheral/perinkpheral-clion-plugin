// SPDX-License-Identifier: MIT
package dev.perinkpheral.clion.simulator

import dev.perinkpheral.core.contract.Contract
import java.io.File
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BundledSimulatorTest {
    private val platforms = listOf("macos", "linux-x86_64", "linux-aarch64")

    @Test
    fun bundlesMatchTheFirmwareCheckoutAndRecordedHashes() {
        val provenance = Properties().apply {
            assertNotNull(this@BundledSimulatorTest.javaClass.getResourceAsStream("/simulator/build.properties"))
                .use { load(it) }
        }
        val firmware = File(System.getProperty("perinkpheral.sdk")).parentFile
        val inputs = SimulatorSourceProvenance.inputs(firmware)
        val differingFiles = SimulatorSourceProvenance.differingFiles(provenance, inputs)
        assertEquals(provenance.getProperty("source.sha256"), SimulatorSourceProvenance.hash(inputs),
            "Bundled simulator source inputs changed: ${differingFiles.joinToString()}. " +
                "Rebuild all platforms and update build.properties with recordSimulatorProvenance.")
        assertTrue(differingFiles.isEmpty(), "Source file manifest differs: ${differingFiles.joinToString()}")
        for (platform in platforms) {
            val bytes = assertNotNull(javaClass.getResourceAsStream("/simulator/$platform/perinkpheral-sim"))
                .use { it.readBytes() }
            val hash = SimulatorSourceProvenance.sha256(bytes)
            assertEquals(provenance.getProperty("$platform.sha256"), hash, "$platform differs from its recorded build")
        }
    }

    @Test
    fun everyBundleContainsTheCurrentSdkImports() {
        val sdk = File(System.getProperty("perinkpheral.sdk"))
        val imports = Contract.of(sdk).calls.map { it.name }
        for (platform in platforms) {
            val resource = assertNotNull(javaClass.getResourceAsStream("/simulator/$platform/perinkpheral-sim"))
            val binary = resource.use { it.readBytes() }.toString(Charsets.ISO_8859_1)
            // Whole strings avoid accepting gfx_text_width as the old gfx_text import.
            for (name in imports) assertTrue(binary.contains("\u0000$name\u0000"), "$platform lacks $name")
        }
    }
}
