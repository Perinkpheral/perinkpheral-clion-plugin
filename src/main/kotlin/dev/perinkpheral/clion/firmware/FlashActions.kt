// SPDX-License-Identifier: MIT
package dev.perinkpheral.clion.firmware

import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import dev.perinkpheral.clion.settings.PerinkpheralSettings
import dev.perinkpheral.clion.toolchain.Toolchain
import dev.perinkpheral.clion.run.AppRuns
import dev.perinkpheral.clion.run.TaskProcessHandler
import dev.perinkpheral.clion.toolchain.Tools
import java.io.File
import java.io.IOException

object Firmware {
    private const val IMAGE_MAGIC = 0xE9

    /** Partitions of the device, read from the SDK checkout. */
    fun partitions(): Map<String, Partition> {
        val table = PerinkpheralSettings.get().tool("firmware/partitions.csv")
        if (!table.isFile) throw IOException("firmware/partitions.csv was not found in the SDK checkout.")
        return PartitionTable.csv(table.readText())
    }

    /** Asks for the image; the build output of the SDK checkout is preselected. */
    fun chooseImage(project: Project): File? {
        val built = PerinkpheralSettings.get().tool("firmware/build/perinkpheral.bin")
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor("bin")
            .withTitle("Firmware Image")
        val preselected = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(built)
        val chosen = FileChooser.chooseFile(descriptor, project, preselected) ?: return null
        return File(chosen.path)
    }

    /** Refuses a file that is not an application image or is larger than the slot. */
    fun check(image: File, slot: Partition) {
        if (image.length() > slot.size) {
            throw IOException("The image is ${image.length()} bytes; the slot holds ${slot.size}.")
        }
        val first = image.inputStream().use { it.read() }
        if (first != IMAGE_MAGIC) throw IOException("${image.name} is not a firmware image.")
    }
}

/** Sends an image to `/api/dev/flash`; the device restarts into it. */
class FlashOverNetworkAction : AnAction(), DumbAware {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        if (!Tools.requireToolchain(project, needsDevice = true)) return
        val image = Firmware.chooseImage(project) ?: return
        try {
            Firmware.check(image, Firmware.partitions().getValue("ota_0"))
        } catch (error: Exception) {
            Tools.notify(project, error.message ?: "Invalid image.", NotificationType.ERROR)
            return
        }
        Tools.runInConsole(project, "Upload Firmware", TaskProcessHandler { AppRuns.flash(this, image) }, null)
    }
}

/**
 * Writes an image over USB with esptool.
 *
 * Written: the `ota_0` slot after verifying the board's layout. Erased: `otadata`,
 * which starts factory recovery; BACK validates and selects the uploaded main firmware.
 * Left as they are: the bootloader, the partition table, `nvs` (settings, Wi-Fi), `appkv`,
 * `appcode`, `apps`, `glyphs`.
 */
class FlashOverUsbAction : AnAction(), DumbAware {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        if (!PerinkpheralSettings.get().isSdkValid) {
            Tools.requireToolchain(project, needsDevice = false)
            return
        }
        val image = Firmware.chooseImage(project) ?: return
        val slot: Partition
        val partitions: Map<String, Partition>
        try {
            partitions = Firmware.partitions()
            slot = partitions.getValue("ota_0")
            partitions.getValue("otadata")
            Firmware.check(image, slot)
        } catch (error: Exception) {
            Tools.notify(project, error.message ?: "Invalid image.", NotificationType.ERROR)
            return
        }
        val port = Messages.showInputDialog(project,
            "${image.name} is written to ota_0 at 0x${slot.offset.toString(16)}.\n" +
                "The board layout is checked first. Settings, Wi-Fi and applications are kept.\n" +
                "After writing, press BACK (Exit recovery) on the device to start the firmware.\n\n" +
                "Serial port (empty: detect):",
            "Upload Firmware over USB", null, "", null) ?: return

        object : Task.Backgroundable(project, "Preparing esptool", true) {
            private lateinit var tool: File

            override fun run(indicator: ProgressIndicator) {
                tool = Toolchain.fetchEsptool(indicator)
            }

            override fun onSuccess() {
                val command = mutableListOf(tool.path, "--chip", "esp32c6")
                Tools.runInConsole(project, "Write Firmware", TaskProcessHandler {
                    UsbFlash.install(image, partitions, port, { arguments, output -> runChild(command + arguments, output) }) { message ->
                        say(message)
                        Tools.notify(project, message)
                    }
                }, null)
            }

            override fun onThrowable(error: Throwable) {
                Tools.notify(project, "esptool: ${error.message}", NotificationType.ERROR)
            }
        }.queue()
    }
}
