// SPDX-License-Identifier: MIT
package dev.perinkpheral.clion.firmware

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.nio.file.Files

data class Partition(val name: String, val offset: Long, val size: Long,
                     val type: Int, val subtype: Int, val flags: Int = 0)

internal object PartitionTable {
    fun csv(text: String): Map<String, Partition> = text.lineSequence()
        .map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        .map { line ->
            val fields = line.split(',').map { it.trim() }
            val type = when (fields[1]) { "app" -> 0; "data" -> 1; else -> fields[1].toIntValue() }
            val subtype = when (fields[2]) {
                "factory" -> 0
                "ota" -> 0
                "phy" -> 1
                "nvs" -> 2
                "littlefs" -> 0x83
                else -> if (fields[2].startsWith("ota_")) 0x10 + fields[2].removePrefix("ota_").toInt()
                        else fields[2].toIntValue()
            }
            val flags = when (fields.getOrNull(5).orEmpty()) {
                "" -> 0
                "encrypted" -> 1
                else -> throw IOException("Unsupported partition flags.")
            }
            Partition(fields[0], java.lang.Long.decode(fields[3]), java.lang.Long.decode(fields[4]), type, subtype, flags)
        }.associateBy { it.name }

    private fun String.toIntValue() = java.lang.Integer.decode(this)

    fun verify(bytes: ByteArray, expected: Map<String, Partition>) {
        fun refuse(): Nothing = throw IOException(
            "The board partition table does not match the SDK recovery layout. Nothing was written. " +
                "Use the release's data-preserving migration-manifest.json through the web flasher " +
                "at https://perinkpheral.dev/flash before uploading firmware over USB.")
        // Erasing the selector is safe only when factory recovery exists.
        if (expected["factory"]?.let { it.type == 0 && it.subtype == 0 } != true || bytes.size != 0x1000) refuse()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val found = mutableMapOf<String, Partition>()
        for (at in bytes.indices step 32) {
            when (buffer.getShort(at).toInt() and 0xffff) {
                0x50aa -> {
                    val name = bytes.copyOfRange(at + 12, at + 28).toString(Charsets.US_ASCII).substringBefore('\u0000')
                    val partition = Partition(name, buffer.getInt(at + 4).toLong() and 0xffffffffL,
                        buffer.getInt(at + 8).toLong() and 0xffffffffL,
                        bytes[at + 2].toInt() and 0xff, bytes[at + 3].toInt() and 0xff, buffer.getInt(at + 28))
                    if (found.put(name, partition) != null) refuse()
                }
                0xebeb -> {
                    val digest = MessageDigest.getInstance("MD5").digest(bytes.copyOfRange(0, at))
                    if (!digest.contentEquals(bytes.copyOfRange(at + 16, at + 32)) || found != expected) refuse()
                    return
                }
                // Older ESP-IDF tables may omit the checksum.
                0xffff -> {
                    if (bytes.copyOfRange(at, bytes.size).any { it != 0xff.toByte() } || found != expected) refuse()
                    return
                }
                else -> refuse()
            }
        }
        refuse()
    }
}

internal object UsbFlash {
    const val COMPLETION = "Firmware written. The device starts in recovery. " +
        "Press BACK (Exit recovery) on the device to validate and start the uploaded firmware."

    fun install(image: File, partitions: Map<String, Partition>, port: String,
                run: (List<String>, (String) -> Unit) -> Int,
                say: (String) -> Unit): Int {
        var selectedPort = port.trim()
        if (selectedPort.isEmpty()) {
            // Probe once, then pin even the partition read to the successful connection.
            val detected = run(listOf("--after", "no-reset", "read-mac")) { line ->
                val plain = line.replace(Regex("\u001B\\[[0-9;]*m"), "").trim()
                Regex("^Connected to ESP32-C6 on (.+):$").matchEntire(plain)?.let {
                    selectedPort = it.groupValues[1].trim()
                }
            }
            if (detected != 0) return detected
            if (selectedPort.isEmpty()) throw IOException("Could not detect the serial port. Enter a port and try again.")
        }
        fun onBoard(arguments: List<String>) = run(listOf("--port", selectedPort) + arguments, {})
        val table = Files.createTempFile("perinkpheral-partitions-", ".bin").toFile()
        try {
            val read = onBoard(listOf("--after", "no-reset", "read-flash", "0x8000", "0x1000", table.path))
            if (read != 0) return read
            PartitionTable.verify(table.readBytes(), partitions)
            val slot = partitions.getValue("ota_0")
            val selector = partitions.getValue("otadata")
            // Clear both boot records before replacing main so an interrupted upload
            // cannot boot the new image under the previous image's VALID record.
            val erase = onBoard(listOf("--after", "no-reset", "erase-region", "0x${selector.offset.toString(16)}", "0x${selector.size.toString(16)}"))
            if (erase != 0) return erase
            val write = onBoard(listOf("write-flash", "0x${slot.offset.toString(16)}", image.path))
            if (write == 0) say(COMPLETION)
            return write
        } finally {
            table.delete()
        }
    }
}
