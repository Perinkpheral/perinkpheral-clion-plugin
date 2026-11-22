// SPDX-License-Identifier: MIT
package dev.perinkpheral.clion.project

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GeneratedFilesTest {
    @Test
    fun concurrentGenerationsThroughCanonicalAliasesPublishCompleteSnapshotsInOrder() {
        val temporary = Files.createTempDirectory("perinkpheral-generation").toFile()
        val executor = Executors.newFixedThreadPool(3)
        val releaseFirst = CountDownLatch(1)
        try {
            val app = File(temporary, "app").apply { mkdirs() }
            val alias = File(temporary, "alias")
            Files.createSymbolicLink(alias.toPath(), app.toPath())
            val output = GeneratedFiles.directory(File(temporary, "generated"), app).apply { mkdirs() }
            val source = File(app, "source").apply { writeText("old") }
            val firstEntered = CountDownLatch(1)
            val secondAttempted = CountDownLatch(1)
            val secondEntered = CountDownLatch(1)
            val first = executor.submit {
                GeneratedFiles.serialized(app) {
                    val snapshot = source.readText()
                    File(output, "strings.h").writeText(snapshot)
                    firstEntered.countDown()
                    check(releaseFirst.await(5, TimeUnit.SECONDS))
                    File(output, "strings.c").writeText(snapshot)
                }
            }
            assertTrue(firstEntered.await(5, TimeUnit.SECONDS))
            source.writeText("new")
            val second = executor.submit {
                secondAttempted.countDown()
                GeneratedFiles.serialized(alias) {
                    secondEntered.countDown()
                    val snapshot = source.readText()
                    File(output, "strings.h").writeText(snapshot)
                    File(output, "strings.c").writeText(snapshot)
                }
            }
            assertTrue(secondAttempted.await(5, TimeUnit.SECONDS))
            // A separate application must still be able to generate while this one is busy.
            val other = File(temporary, "other").apply { mkdirs() }
            assertEquals("independent", executor.submit<String> {
                GeneratedFiles.serialized(other) { "independent" }
            }.get(5, TimeUnit.SECONDS))
            assertFalse(secondEntered.await(200, TimeUnit.MILLISECONDS))
            releaseFirst.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
            assertEquals("new", File(output, "strings.h").readText())
            assertEquals("new", File(output, "strings.c").readText())
            alias.delete()
        } finally {
            releaseFirst.countDown()
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            temporary.deleteRecursively()
        }
    }

    @Test
    fun failedGenerationReleasesTheApplicationLock() {
        val temporary = Files.createTempDirectory("perinkpheral-generation-failure").toFile()
        val executor = Executors.newSingleThreadExecutor()
        try {
            assertFailsWith<IOException> {
                GeneratedFiles.serialized(temporary) { throw IOException("Invalid strings") }
            }
            assertEquals("recovered", executor.submit<String> {
                GeneratedFiles.serialized(temporary) { "recovered" }
            }.get(5, TimeUnit.SECONDS))
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(5, TimeUnit.SECONDS)
            temporary.deleteRecursively()
        }
    }

    @Test
    fun separateCheckoutsCannotOverwriteEachOthersHeaders() {
        val temporary = Files.createTempDirectory("perinkpheral-headers").toFile()
        try {
            val first = File(temporary, "main/clock").apply { mkdirs() }
            val second = File(temporary, "feature/clock").apply { mkdirs() }
            val root = File(temporary, "generated")
            val firstOutput = GeneratedFiles.directory(root, first).apply { mkdirs() }
            val secondOutput = GeneratedFiles.directory(root, second).apply { mkdirs() }
            File(firstOutput, "strings.h").writeText("first")
            File(secondOutput, "strings.h").writeText("second")
            assertNotEquals(firstOutput, secondOutput)
            assertEquals("first", File(firstOutput, "strings.h").readText())
            assertEquals("second", File(secondOutput, "strings.h").readText())
            assertEquals(64, firstOutput.parentFile.name.length)
        } finally {
            temporary.deleteRecursively()
        }
    }

    @Test
    fun canonicalAliasesShareTheSameDirectory() {
        val temporary = Files.createTempDirectory("perinkpheral-alias").toFile()
        try {
            val app = File(temporary, "source/clock").apply { mkdirs() }
            val root = File(temporary, "generated")
            val expected = GeneratedFiles.directory(root, app)
            assertEquals(expected, GeneratedFiles.directory(root, File(app, ".")))
            assertEquals(expected, GeneratedFiles.directory(root, File(app, "../clock")))
            val alias = File(temporary, "alias")
            Files.createSymbolicLink(alias.toPath(), app.parentFile.toPath())
            assertEquals(expected, GeneratedFiles.directory(root, File(alias, "clock")))
            alias.delete()
        } finally {
            temporary.deleteRecursively()
        }
    }

    @Test
    fun unsupportedSdkHasAnExplicitUpdateInstruction() {
        val temporary = Files.createTempDirectory("perinkpheral-sdk").toFile()
        try {
            val module = File(temporary, "sdk/cmake/perinkpheral.cmake")
            module.parentFile.mkdirs()
            module.writeText("set(generated old)")
            val problem = assertFailsWith<IOException> { GeneratedFiles.requireSdkSupport(temporary) }
            assertEquals(GeneratedFiles.SDK_UPDATE, problem.message)
            module.writeText("if(PERINKPHERAL_GENERATED_BY_PATH)\nendif()")
            GeneratedFiles.requireSdkSupport(temporary)
        } finally {
            temporary.deleteRecursively()
        }
    }
}
