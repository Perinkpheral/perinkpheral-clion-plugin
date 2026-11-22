// SPDX-License-Identifier: MIT
package dev.perinkpheral.clion.project

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** The SDK's CMake module uses the same canonical path and full SHA256. */
object GeneratedFiles {
    const val SDK_UPDATE = "Update the perinkpheral SDK checkout to use this plugin's generated headers."
    const val CMAKE_OPTION = "PERINKPHERAL_GENERATED_BY_PATH"

    private val generationLocks = ConcurrentHashMap<String, Any>()

    /** Keep input reads, generation and publication together, including calls through aliases. */
    internal fun <T> serialized(appDir: File, generate: () -> T): T =
        synchronized(generationLocks.computeIfAbsent(appDir.canonicalPath) { Any() }) { generate() }

    fun directory(root: File, appDir: File): File {
        val path = appDir.canonicalFile.invariantSeparatorsPath
        val hash = MessageDigest.getInstance("SHA-256").digest(path.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(File(root, hash), appDir.absoluteFile.normalize().name)
    }

    fun requireSdkSupport(sdk: File) {
        val module = File(sdk, "sdk/cmake/perinkpheral.cmake")
        if (!module.isFile || !module.readText().contains(CMAKE_OPTION)) throw IOException(SDK_UPDATE)
    }
}
