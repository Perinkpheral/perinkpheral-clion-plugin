// SPDX-License-Identifier: MIT
package dev.perinkpheral.clion.project

import org.junit.Assume.assumeNoException
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolchainFileTest {
    @Test
    fun emptyPluginAddressPreservesDeveloperCmakeOption() = withCmakeProject { project ->
        configure(project, "", "desk.local", "desk.local")
        configure(project, "", null, "desk.local")
    }

    @Test
    fun clearingPluginAddressRemovesOnlyItsCachedValue() = withCmakeProject { project ->
        configure(project, "previous-board.local", null, "previous-board.local")
        configure(project, "", null, "")
        configure(project, "", null, "")
        // Ownership is released: even the old address can now be selected explicitly.
        configure(project, "", "previous-board.local", "previous-board.local")
        configure(project, "", null, "previous-board.local")
    }

    @Test
    fun clearingPluginAddressPreservesDeveloperReplacement() = withCmakeProject { project ->
        configure(project, "previous-board.local", null, "previous-board.local")
        configure(project, "", "desk.local", "desk.local")
        configure(project, "", null, "desk.local")
    }

    private fun withCmakeProject(test: (File) -> Unit) {
        val project = Files.createTempDirectory("perinkpheral-toolchain").toFile()
        try {
            File(project, "CMakeLists.txt").writeText("""
                cmake_minimum_required(VERSION 3.20)
                project(PluginHostCache NONE)
                include("${'$'}{CMAKE_CURRENT_SOURCE_DIR}/toolchain.cmake")
                # The SDK initializes this entry without FORCE when making run targets.
                set(PERINKPHERAL_HOST "${'$'}ENV{PERINKPHERAL_HOST}" CACHE STRING "The device's address")
                file(WRITE "${'$'}{CMAKE_BINARY_DIR}/host.txt" "${'$'}{PERINKPHERAL_HOST}\n${'$'}ENV{PERINKPHERAL_HOST}")
            """.trimIndent())
            test(project)
        } finally {
            project.deleteRecursively()
        }
    }

    private fun configure(project: File, pluginHost: String, developerHost: String?, expected: String) {
        File(project, ToolchainFile.NAME).writeText(ToolchainFile.content("", "", pluginHost, File(project, "generated")))
        val build = File(project, "build")
        val command = mutableListOf("cmake", "-S", project.path, "-B", build.path)
        if (developerHost != null) command += "-DPERINKPHERAL_HOST=$developerHost"
        val log = File(project, "cmake.log")
        val process = try {
            ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log).apply {
                environment()["PERINKPHERAL_HOST"] = "inherited-old-board.local"
            }.start()
        } catch (error: IOException) {
            assumeNoException("CMake must be installed to test the generated cache behavior", error)
            return
        }
        try {
            assertTrue(process.waitFor(30, TimeUnit.SECONDS), "CMake configure timed out")
            assertEquals(0, process.exitValue(), log.readText())
        } finally {
            if (process.isAlive) process.destroyForcibly().waitFor()
        }
        assertEquals("$expected\n$pluginHost", File(build, "host.txt").readText())
        val cached = File(build, "CMakeCache.txt").readLines().single { it.startsWith("PERINKPHERAL_HOST:") }
        assertEquals(expected, cached.substringAfter('='))
    }
}
