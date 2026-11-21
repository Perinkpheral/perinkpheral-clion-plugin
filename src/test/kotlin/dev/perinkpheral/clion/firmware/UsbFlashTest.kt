// SPDX-License-Identifier: MIT
package dev.perinkpheral.clion.firmware

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbFlashTest {
    private val current = PartitionTable.csv("""
        nvs, data, nvs, 0x9000, 0x6000
        otadata, data, ota, 0xf000, 0x2000
        phy_init, data, phy, 0x11000, 0x1000
        appkv, data, nvs, 0x12000, 0xe000
        factory, app, factory, 0x20000, 0xe0000
        ota_0, app, ota_0, 0x100000, 0x200000
        appcode, data, 0x40, 0x300000, 0x50000
        apps, data, littlefs, 0x350000, 0xa0000
        glyphs, data, 0x41, 0x3f0000, 0x10000
    """.trimIndent())

    private fun table(partitions: List<Partition> = current.values.toList(), checksum: Boolean = true): ByteArray {
        val bytes = ByteArray(0x1000) { 0xff.toByte() }
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for ((index, partition) in partitions.withIndex()) {
            buffer.position(index * 32)
            buffer.putShort(0x50aa).put(partition.type.toByte()).put(partition.subtype.toByte())
                .putInt(partition.offset.toInt()).putInt(partition.size.toInt())
                .put(partition.name.toByteArray().copyOf(16)).putInt(partition.flags)
        }
        if (checksum) {
            val at = partitions.size * 32
            buffer.putShort(at, 0xebeb.toShort())
            val digest = MessageDigest.getInstance("MD5").digest(bytes.copyOfRange(0, at))
            digest.copyInto(bytes, at + 16)
        }
        return bytes
    }

    private class FlashRun(val bytes: ByteArray, val failStep: Int = -1) {
        val commands = mutableListOf<List<String>>()
        val messages = mutableListOf<String>()
        var temporary: File? = null

        fun run(arguments: List<String>, output: (String) -> Unit): Int {
            commands += arguments
            if (commands.size == failStep) return 2
            if ("read-flash" in arguments) {
                temporary = File(arguments.last()).apply { writeBytes(bytes) }
            }
            return 0
        }
    }

    @Test
    fun matchingBoardIsReadBeforeWritingAndErasing() {
        val run = FlashRun(table())
        assertEquals(0, UsbFlash.install(File("firmware.bin"), current, " /dev/selected ", run::run, run.messages::add))
        assertEquals(listOf("--after", "no-reset", "read-flash", "0x8000", "0x1000"), run.commands[0].drop(2).dropLast(1))
        assertEquals(listOf("--after", "no-reset", "write-flash", "0x100000", "firmware.bin"), run.commands[1].drop(2))
        assertEquals(listOf("erase-region", "0xf000", "0x2000"), run.commands[2].drop(2))
        assertTrue(run.commands.all { it.take(2) == listOf("--port", "/dev/selected") })
        assertFalse(run.temporary!!.exists())
    }

    @Test
    fun emptyPortIsDetectedOnceAndPinnedForAllFlashSteps() {
        val run = FlashRun(table())
        val commands = mutableListOf<List<String>>()
        val result = UsbFlash.install(File("firmware.bin"), current, "  ", { arguments, output ->
            commands += arguments
            if ("read-mac" in arguments) {
                output("Serial port /dev/wrong-board")
                output("Connecting... failed")
                output("\u001B[32mConnected to ESP32-C6 on /dev/right-board:\u001B[0m")
                0
            } else run.run(arguments, output)
        }, run.messages::add)
        assertEquals(0, result)
        assertEquals(listOf("--after", "no-reset", "read-mac"), commands.first())
        assertEquals(4, commands.size)
        assertTrue(commands.drop(1).all { it.take(2) == listOf("--port", "/dev/right-board") })
        assertEquals(3, run.commands.size)
    }

    @Test
    fun detectionFailureOrUnrecognizedOutputCannotReadWriteOrErase() {
        for (code in listOf(0, 2)) {
            val commands = mutableListOf<List<String>>()
            val messages = mutableListOf<String>()
            val flash = {
                UsbFlash.install(File("image"), current, "", { arguments, output ->
                    commands += arguments
                    output("Serial port /dev/not-confirmed")
                    code
                }, messages::add)
            }
            if (code == 0) assertFailsWith<IOException> { flash() }
            else assertEquals(code, flash())
            assertEquals(1, commands.size)
            assertTrue("read-mac" in commands.single())
            assertTrue(messages.isEmpty())
        }
    }

    @Test
    fun oldTwoSlotLayoutIsRefusedBeforeAnyWrite() {
        val old = current.values.filter { it.name != "factory" && it.name != "ota_0" } +
            Partition("ota_0", 0x20000, 0x170000, 0, 0x10) + Partition("ota_1", 0x190000, 0x170000, 0, 0x11)
        val run = FlashRun(table(old))
        val error = assertFailsWith<IOException> {
            UsbFlash.install(File("firmware.bin"), current, " /dev/selected ", run::run, run.messages::add)
        }
        assertTrue(error.message!!.contains("data-preserving migration-manifest.json"))
        assertEquals(1, run.commands.size)
        assertTrue(run.messages.isEmpty())
        assertFalse(run.temporary!!.exists())
    }

    @Test
    fun changedTypesSizesFlagsAndDuplicateEntriesAreRefused() {
        val slot = current.getValue("ota_0")
        for (changed in listOf(slot.copy(type = 1), slot.copy(subtype = 0x11),
            slot.copy(size = slot.size - 4096), slot.copy(flags = 1))) {
            val partitions = current.values.filter { it.name != slot.name } + changed
            assertFailsWith<IOException> { PartitionTable.verify(table(partitions), current) }
        }
        assertFailsWith<IOException> { PartitionTable.verify(table(current.values.toList() + slot), current) }
        assertFailsWith<IOException> { PartitionTable.verify(table(), current - "factory") }
    }

    @Test
    fun corruptOrTruncatedReadCannotAuthorizeAWrite() {
        val corrupted = table().apply { this[current.size * 32 + 16] = 0 }
        for (bytes in listOf(corrupted, table().copyOf(64), ByteArray(4096))) {
            val run = FlashRun(bytes)
            assertFailsWith<IOException> { UsbFlash.install(File("image"), current, " /dev/selected ", run::run, run.messages::add) }
            assertEquals(1, run.commands.size)
        }
        PartitionTable.verify(table(checksum = false), current)
    }

    @Test
    fun failuresStopLaterStepsAndDoNotReportCompletion() {
        for (step in 1..3) {
            val run = FlashRun(table(), failStep = step)
            assertEquals(2, UsbFlash.install(File("image"), current, " /dev/selected ", run::run, run.messages::add))
            assertEquals(step, run.commands.size)
            assertTrue(run.messages.isEmpty())
        }
    }

    @Test
    fun successfulFlashExplainsHowToStartMainFirmware() {
        val run = FlashRun(table())
        UsbFlash.install(File("image"), current, " /dev/selected ", run::run, run.messages::add)
        val message = run.messages.single()
        assertTrue(message.contains("starts in recovery"))
        assertTrue(message.contains("Press BACK (Exit recovery)"))
        assertTrue(message.contains("validate and start"))
    }
}
