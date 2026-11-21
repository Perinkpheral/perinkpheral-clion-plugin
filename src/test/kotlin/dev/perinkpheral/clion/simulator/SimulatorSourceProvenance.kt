// SPDX-License-Identifier: MIT
package dev.perinkpheral.clion.simulator

import java.io.File
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.Properties

/** Source inputs of tools/simulator/Makefile; no compiler, build output or Git HEAD is hashed. */
internal object SimulatorSourceProvenance {
    // SOURCES, excluding sim.c (covered by tools/simulator below).
    private val compiledSources = """
        firmware/components/appsys/answer_headers.c
        firmware/components/appsys/app.c
        firmware/components/appsys/app_api_draw.c
        firmware/components/appsys/app_api_ui.c
        firmware/components/appsys/app_api_data.c
        firmware/components/appsys/app_api_time.c
        firmware/components/appsys/app_api_sys.c
        firmware/components/appsys/app_scene.c
        firmware/components/appsys/app_delivery.c
        firmware/components/appstore/app_store.c
        firmware/components/appstore/app_manifest.c
        firmware/components/appstore/poll_schedule.c
        firmware/components/appstore/json_tree.c
        firmware/components/appstore/text_template.c
        sdk/lib/json_pick.c
        firmware/components/ui/wm.c
        firmware/components/ui/notice.c
        firmware/components/ui/kit.c
        firmware/components/ui/scrollbar.c
        firmware/components/ui/list.c
        firmware/components/gfx/gfx.c
        firmware/components/gfx/gfx_icons.c
        firmware/components/gfx/gfx_icon_art.c
        firmware/components/words/words.c
        firmware/components/gfx/font_square.c
        firmware/main/screens.c
        firmware/components/appconf/appconf.c
        firmware/main/header.c
        firmware/main/scene_menu.c
        firmware/components/deadlines/deadline_table.c
        firmware/components/qr/qrcodegen/qrcodegen.c
    """.trimIndent().lines()

    // INCLUDES, excluding BUILD, which contains generated files, not source inputs.
    private val includeDirectories = listOf(
        "firmware/components/appsys/include", "firmware/components/appstore/include",
        "firmware/components/ui/include", "firmware/components/gfx/include",
        "firmware/components/words/include", "firmware/components/gfx/fonts",
        "firmware/components/qr/include", "firmware/components/qr/qrcodegen",
        "firmware/components/wasm3/wasm3", "sdk", "sdk/lib",
        "firmware/components/board/include", "firmware/components/appconf/include",
        "firmware/components/deadlines/include", "firmware/main"
    )
    private val include = Regex("""(?m)^\s*#\s*include\s*[<"]([^>"]+)[>"]""")

    fun inputs(firmware: File): Map<String, ByteArray> {
        require(firmware.isDirectory) { "Missing firmware checkout: $firmware" }
        val sources = sortedMapOf<String, ByteArray>()
        val directories = includeDirectories.map { File(firmware, it) }
        fun add(file: File) {
            val path = file.relativeTo(firmware).invariantSeparatorsPath
            // A removed source must reach the manifest comparison so the failure lists it.
            if (path in sources || !file.isFile) return
            val bytes = file.readBytes()
            sources[path] = bytes
            // Follow local headers (including fonts and wasm3 headers), but not system headers
            // or generated host_imports.inc/page.inc. Their source prerequisites are added below.
            if (file.extension in listOf("c", "h")) {
                for (match in include.findAll(bytes.toString(Charsets.UTF_8))) {
                    val name = match.groupValues[1]
                    (listOf(file.parentFile) + directories).asSequence()
                        .map { File(it, name).normalize() }.firstOrNull { it.isFile }?.let { add(it) }
                }
            }
        }
        // Makefile and all simulator sources, including new files, excluding its ignored build/.
        val simulator = File(firmware, "tools/simulator")
        simulator.walkTopDown().onEnter { it != File(simulator, "build") }
            .filter { it.isFile }.forEach { add(it) }
        compiledSources.forEach { add(File(firmware, it)) }
        // WASM3_SOURCES := $(wildcard $(WASM3)/*.c)
        File(firmware, "firmware/components/wasm3/wasm3").listFiles().orEmpty()
            .filter { it.extension == "c" }.forEach { add(it) }
        // Prerequisites of generated host_imports.inc and page.inc.
        add(File(firmware, "sdk/host_api.h"))
        File(firmware, "sdk/host").listFiles().orEmpty().filter { it.extension == "h" }.forEach { add(it) }
        add(File(firmware, "tools/cli/src/main/resources/dev/perinkpheral/cli/page.html"))
        return sources
    }

    fun hash(inputs: Map<String, ByteArray>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        // Sorted UTF-8 relative paths and raw contents, each prefixed by its 8-byte BE length.
        // Framing prevents ambiguity between paths, contents and file boundaries.
        for ((path, contents) in inputs.toSortedMap()) {
            for (bytes in listOf(path.toByteArray(Charsets.UTF_8), contents)) {
                digest.update(ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
                digest.update(bytes)
            }
        }
        return digest.digest().hex()
    }

    fun fileHashes(inputs: Map<String, ByteArray>): Map<String, String> =
        inputs.toSortedMap().mapValues { sha256(it.value) }

    fun differingFiles(recorded: Properties, inputs: Map<String, ByteArray>): List<String> {
        val expected = recorded.stringPropertyNames().filter { it.startsWith("source.file.") }
            .associate { it.removePrefix("source.file.") to recorded.getProperty(it) }
        val actual = fileHashes(inputs)
        return (expected.keys + actual.keys).sorted().filter { expected[it] != actual[it] }
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).hex()
    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    /** Run recordSimulatorProvenance only after copying all three builds into resources. */
    @JvmStatic
    fun main(args: Array<String>) {
        val firmware = File(args[0]).absoluteFile
        val resources = File(args[1])
        val inputs = inputs(firmware)
        File(resources, "build.properties").writeText(buildString {
            appendLine("# Built with tools/simulator/Makefile; source paths are relative to the firmware root.")
            appendLine("# source.sha256: sorted paths and raw contents, each framed by an 8-byte BE length.")
            appendLine("source.sha256=${hash(inputs)}")
            for ((path, hash) in fileHashes(inputs)) appendLine("source.file.$path=$hash")
            for (platform in listOf("macos", "linux-x86_64", "linux-aarch64")) {
                appendLine("$platform.sha256=${sha256(File(resources, "$platform/perinkpheral-sim").readBytes())}")
            }
        })
    }
}
