// SPDX-License-Identifier: MIT
package dev.perinkpheral.clion.simulator

import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class SimulatorSourceProvenanceTest {
    @Test
    fun unrelatedFirmwareChangesAndBuildOutputsDoNotChangeProvenance() = withFirmwareCopy { root, original ->
        write(root, ".git/HEAD", "a later firmware commit")
        write(root, "firmware/main/unrelated.c", "new unrelated firmware code")
        write(root, "tools/simulator/build/host_imports.inc", "generated imports")
        write(root, "tools/simulator/build/wasm3/m3_env.o", "compiler output")
        assertEquals(SimulatorSourceProvenance.hash(original), SimulatorSourceProvenance.hash(
            SimulatorSourceProvenance.inputs(root)))
    }

    @Test
    fun reportsChangedAddedAndRemovedInputs() = withFirmwareCopy { root, original ->
        val changed = listOf(
            "tools/simulator/sim.c", "tools/simulator/Makefile",
            "firmware/components/appstore/poll_schedule.c", "sdk/lib/json_pick.c",
            "firmware/components/appsys/include/app.h", "sdk/host/net.h",
            "tools/cli/src/main/resources/dev/perinkpheral/cli/page.html"
        )
        changed.forEach { File(root, it).appendText("\n/* changed */\n") }
        val added = listOf("tools/simulator/new.c", "firmware/components/wasm3/wasm3/new.c")
        added.forEach { write(root, it, "/* new */") }
        val removed = "firmware/components/ui/list.c"
        check(File(root, removed).delete())
        val inputs = SimulatorSourceProvenance.inputs(root)
        val recorded = Properties().apply {
            SimulatorSourceProvenance.fileHashes(original).forEach { (path, hash) ->
                setProperty("source.file.$path", hash)
            }
        }
        assertNotEquals(SimulatorSourceProvenance.hash(original), SimulatorSourceProvenance.hash(inputs))
        assertEquals((changed + added + removed).sorted(),
            SimulatorSourceProvenance.differingFiles(recorded, inputs))
    }

    private fun withFirmwareCopy(block: (File, Map<String, ByteArray>) -> Unit) {
        val firmware = File(System.getProperty("perinkpheral.sdk")).parentFile
        val inputs = SimulatorSourceProvenance.inputs(firmware)
        val root = Files.createTempDirectory("simulator-provenance-").toFile()
        try {
            inputs.forEach { (path, bytes) ->
                File(root, path).apply { parentFile.mkdirs(); writeBytes(bytes) }
            }
            block(root, inputs)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun write(root: File, path: String, text: String) {
        File(root, path).apply { parentFile.mkdirs(); writeText(text) }
    }
}
