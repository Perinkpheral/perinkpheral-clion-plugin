// SPDX-License-Identifier: MIT
package dev.perinkpheral.clion.simulator

import java.io.File
import java.nio.file.Files
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SimulatorSourceProvenanceTest {
    @Test
    fun coversEverySourceCompiledBySimulatorMakefile() {
        val firmware = File(System.getProperty("perinkpheral.sdk")).parentFile
        // Expand the Makefile's own variables without building or generating any files.
        val process = ProcessBuilder("make", "--no-print-directory", "-rR", "-s",
            "-f", "Makefile", "-f", "-", "provenance-sources")
            .directory(File(firmware, "tools/simulator"))
            .redirectErrorStream(true)
            .start()
        process.outputStream.bufferedWriter().use {
            it.write(".PHONY: provenance-sources\nprovenance-sources:\n" +
                "\t@printf '%s\\n' $(SOURCES) $(WASM3_SOURCES)\n")
        }
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), output)
        val compiled = output.lineSequence().filter { it.isNotBlank() }.map {
            File(firmware, "tools/simulator/$it").normalize().relativeTo(firmware).invariantSeparatorsPath
        }.toSet()
        assertTrue("tools/simulator/sim.c" in compiled, "Makefile source list is empty or invalid: $output")
        val missing = compiled - SimulatorSourceProvenance.inputs(firmware).keys
        assertTrue(missing.isEmpty(), "Sources compiled by the simulator Makefile lack provenance: " +
            missing.sorted().joinToString())
    }

    @Test
    fun unrelatedFirmwareChangesAndBuildOutputsDoNotChangeProvenance() = withFirmwareCopy { root, original ->
        write(root, ".git/HEAD", "a later firmware commit")
        write(root, "firmware/main/unrelated.c", "new unrelated firmware code")
        write(root, "tools/simulator/build/host_imports.inc", "generated imports")
        write(root, "tools/simulator/build/wasm3/m3_env.o", "compiler output")
        for (name in listOf("README.md", "test_jobs.c", ".gitignore", ".DS_Store", "unused.c", "unused.h")) {
            write(root, "tools/simulator/$name", "unrelated simulator file")
        }
        assertEquals(SimulatorSourceProvenance.hash(original), SimulatorSourceProvenance.hash(
            SimulatorSourceProvenance.inputs(root)))
    }

    @Test
    fun reportsChangedAddedAndRemovedInputs() = withFirmwareCopy { root, original ->
        val changed = listOf(
            "tools/simulator/sim.c", "tools/simulator/Makefile",
            "firmware/components/appstore/poll_schedule.c", "sdk/lib/json_pick.c",
            "firmware/components/appstore/poll_round.c", "firmware/components/ui/back_switch.c",
            "firmware/main/question_press.c",
            "firmware/components/appsys/include/app.h", "sdk/host/net.h",
            "firmware/components/wasm3/m3_budget_compile.c",
            "firmware/components/wasm3/wasm3/m3_compile.c",
            "tools/cli/src/main/kotlin/dev/perinkpheral/cli/ContractTools.kt",
            "tools/core/src/main/kotlin/dev/perinkpheral/core/contract/Contract.kt",
            "tools/cli/src/main/resources/dev/perinkpheral/cli/page.html"
        )
        changed.forEach { File(root, it).appendText("\n/* changed */\n") }
        val added = listOf("firmware/components/wasm3/wasm3/new.c", "firmware/components/wasm3/wasm3/new.h")
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

    @Test
    fun includesRequireExactCaseForEveryPathComponent() = withFirmwareCopy { root, _ ->
        write(root, "tools/simulator/CaseProbe.h", "/* wrong case */")
        write(root, "sdk/lib/caseprobe.h", "/* exact case in a later include directory */")
        write(root, "tools/simulator/CaseDir/probe.h", "/* wrong directory case */")
        File(root, "tools/simulator/sim.c").appendText(
            "\n#include \"caseprobe.h\"\n#include \"casedir/probe.h\"\n")
        val inputs = SimulatorSourceProvenance.inputs(root)
        assertTrue("sdk/lib/caseprobe.h" in inputs)
        assertFalse(inputs.keys.any { it.startsWith("tools/simulator/Case") ||
            it == "tools/simulator/caseprobe.h" || it == "tools/simulator/casedir/probe.h" })
    }

    @Test
    fun followsIncludedFilesAndEscapesPropertiesPaths() = withFirmwareCopy { root, _ ->
        val header = "tools/simulator/header = x.h"
        val nested = "tools/simulator/nested.inc"
        write(root, header, "#include \"nested.inc\"\n")
        write(root, nested, "/* included data */")
        File(root, "tools/simulator/sim.c").appendText("\n#include \"header = x.h\"\n")
        val resources = File(root, "bundles")
        val platforms = listOf("macos", "linux-x86_64", "linux-aarch64")
        platforms.forEach { write(resources, "$it/perinkpheral-sim", "fixture for $it") }
        SimulatorSourceProvenance.main(arrayOf(root.path, resources.path))
        val recorded = Properties().apply {
            File(resources, "build.properties").inputStream().use { load(it) }
        }
        val inputs = SimulatorSourceProvenance.inputs(root)
        assertTrue(header in inputs)
        assertTrue(nested in inputs)
        assertEquals(SimulatorSourceProvenance.sha256(File(root, header).readBytes()),
            recorded.getProperty("source.file.$header"))
        assertEquals(SimulatorSourceProvenance.hash(inputs), recorded.getProperty("source.sha256"))
        assertEquals(emptyList(), SimulatorSourceProvenance.differingFiles(recorded, inputs))
        for (platform in platforms) {
            assertEquals(SimulatorSourceProvenance.sha256(File(resources, "$platform/perinkpheral-sim").readBytes()),
                recorded.getProperty("$platform.sha256"))
        }
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
