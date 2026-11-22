// SPDX-License-Identifier: MIT
package dev.perinkpheral.clion.project

import dev.perinkpheral.clion.settings.PerinkpheralSettings
import dev.perinkpheral.core.build.BuildFlags
import java.io.File

/**
 * `~/.perinkpheral/toolchain.cmake`: the plugin's settings as CMake reads them.
 *
 * An application's CMakeLists.txt written by the plugin includes this file before anything
 * else, and then the SDK's own module, sdk/cmake/perinkpheral.cmake: CMake finds the SDK,
 * wasi-sdk and the device without environment variables - CLion does not start from a shell -
 * the build's flags as the core has them, and the headers made of the strings and pictures
 * where the plugin puts them, under the canonical application path's hash. Nothing asks for Java.
 */
object ToolchainFile {
    const val NAME = "toolchain.cmake"

    val file: File get() = File(PerinkpheralSettings.managedDirectory(), NAME)

    private val generatedRoot: File get() = File(PerinkpheralSettings.managedDirectory(), "generated")

    /** Where the headers made of an application's strings and pictures go. */
    fun generated(appDir: File): File = GeneratedFiles.directory(generatedRoot, appDir)

    /** CMake's own spelling of a path: forward slashes on every system. */
    private fun cmakePath(path: String): String = path.replace('\\', '/').replace("\"", "\\\"")

    /** Writes the file from the current settings. Returns true when its content changed. */
    fun write(): Boolean {
        val settings = PerinkpheralSettings.get()
        val text = content(settings.sdkPath, settings.wasiSdkPath, settings.deviceHost, generatedRoot)
        val target = file
        if (target.isFile && target.readText() == text) return false
        target.parentFile.mkdirs()
        target.writeText(text)
        return true
    }

    internal fun content(sdkPath: String, wasiSdkPath: String, deviceHost: String, generatedRoot: File): String {
        val sdk = cmakePath(sdkPath)
        val wasi = cmakePath(wasiSdkPath)
        val host = cmakePath(deviceHost)
        return buildString {
            appendLine("# Written by the perINKpheral plugin for CLion from its settings.")
            appendLine("# Rewritten whenever they change: edit them in Settings | Tools | perINKpheral.")
            if (sdk.isNotBlank()) {
                appendLine("set(PERINKPHERAL \"$sdk\")")
                appendLine("set(ENV{PERINKPHERAL} \"$sdk\")")
            }
            if (wasi.isNotBlank()) appendLine("set(ENV{WASI_SDK_PATH} \"$wasi\")")
            if (host.isNotBlank()) {
                appendLine("set(ENV{PERINKPHERAL_HOST} \"$host\")")
                appendLine("set(PERINKPHERAL_HOST \"$host\" CACHE STRING \"The device's address\" FORCE)")
                appendLine("set(_PERINKPHERAL_PLUGIN_HOST \"$host\" CACHE INTERNAL \"Last device address set by the plugin\" FORCE)")
            } else {
                // Each build cache owns its history. Preserve a developer's -D override.
                appendLine("if(DEFINED CACHE{_PERINKPHERAL_PLUGIN_HOST} AND")
                appendLine("   \"\$CACHE{PERINKPHERAL_HOST}\" STREQUAL \"\$CACHE{_PERINKPHERAL_PLUGIN_HOST}\")")
                appendLine("    unset(PERINKPHERAL_HOST CACHE)")
                appendLine("endif()")
                appendLine("unset(_PERINKPHERAL_PLUGIN_HOST CACHE)")
            }
            // strings.h and pictures.h: made by the plugin, not by the command line.
            appendLine("set(PERINKPHERAL_GENERATED \"${cmakePath(generatedRoot.path)}\")")
            appendLine("set(${GeneratedFiles.CMAKE_OPTION} ON)")
            appendLine("if(EXISTS \"\${PERINKPHERAL}/sdk/cmake/perinkpheral.cmake\")")
            appendLine("    file(STRINGS \"\${PERINKPHERAL}/sdk/cmake/perinkpheral.cmake\" perinkpheral_generated_support REGEX \"${GeneratedFiles.CMAKE_OPTION}\")")
            appendLine("    if(NOT perinkpheral_generated_support)")
            appendLine("        message(FATAL_ERROR \"${GeneratedFiles.SDK_UPDATE}\")")
            appendLine("    endif()")
            appendLine("endif()")
            // The build's flags, so that what CLion compiles is what the device gets.
            val compile = BuildFlags.cmake(listOf(BuildFlags.TARGET) + BuildFlags.compile)
            val link = BuildFlags.cmake(listOf(BuildFlags.TARGET) + BuildFlags.link)
            appendLine("set(PERINKPHERAL_COMPILE_FLAGS \"$compile\")")
            appendLine("set(PERINKPHERAL_LINK_FLAGS \"$link\")")
        }
    }

    /** Makes strings.h, strings.c and pictures.h of an application for the code to read.
     * Returns null, or what is wrong with its strings or pictures; then the headers made
     * before stay, and the code keeps its names. */
    fun writeHeaders(appDir: File): String? = try {
        GeneratedFiles.serialized(appDir) {
            val settings = PerinkpheralSettings.get()
            if (!settings.isSdkValid) return@serialized null
            GeneratedFiles.requireSdkSupport(File(settings.sdkPath))
            settings.builder {}.headers(appDir, generated(appDir))
            null
        }
    } catch (error: Exception) {
        error.message ?: error.javaClass.simpleName
    }
}
