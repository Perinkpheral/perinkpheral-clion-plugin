// SPDX-License-Identifier: MIT
package dev.perinkpheral.clion.simulator

import dev.perinkpheral.core.contract.Contract
import java.io.File
import java.security.MessageDigest
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
        val git = ProcessBuilder("git", "-C", firmware.path, "rev-parse", "HEAD")
            .redirectErrorStream(true).start()
        val commit = git.inputStream.bufferedReader().use { it.readText().trim() }
        assertEquals(0, git.waitFor(), "Cannot read firmware checkout commit: $commit")
        assertEquals(commit, provenance.getProperty("firmware.commit"),
            "Bundled simulators are behind the firmware checkout. Rebuild all platforms and update build.properties.")
        for (platform in platforms) {
            val bytes = assertNotNull(javaClass.getResourceAsStream("/simulator/$platform/perinkpheral-sim"))
                .use { it.readBytes() }
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
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
