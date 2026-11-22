// SPDX-License-Identifier: MIT
package dev.perinkpheral.clion.device

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.JBUI
import dev.perinkpheral.clion.settings.PerinkpheralSettings
import dev.perinkpheral.core.device.DeviceButton
import dev.perinkpheral.core.device.DeviceClient
import dev.perinkpheral.core.device.LogFollower
import dev.perinkpheral.clion.simulator.Simulator
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.datatransfer.UnsupportedFlavorException
import java.awt.event.ActionEvent
import java.awt.image.BufferedImage
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea

class DeviceToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = DevicePanel(toolWindow)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        content.setDisposer(panel)
        toolWindow.contentManager.addContent(content)
    }
}

/** The panel's frame, scaled by a whole factor so that the pixels stay square. */
private class ScreenView : JPanel() {
    var frame: BufferedImage? = null
        set(value) {
            field = value
            repaint()
        }

    init {
        preferredSize = Dimension(FRAME_WIDTH, FRAME_HEIGHT)
        minimumSize = Dimension(FRAME_WIDTH / 2, FRAME_HEIGHT / 2)
    }

    override fun paintComponent(graphics: Graphics) {
        super.paintComponent(graphics)
        val image = frame ?: return
        val whole = minOf(width / image.width, height / image.height)
        val drawnWidth: Int
        val drawnHeight: Int
        if (whole >= 1) {
            drawnWidth = image.width * whole
            drawnHeight = image.height * whole
        } else {
            val scale = minOf(width.toDouble() / image.width, height.toDouble() / image.height)
            drawnWidth = (image.width * scale).toInt()
            drawnHeight = (image.height * scale).toInt()
        }
        val g = graphics as Graphics2D
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            if (whole >= 1) RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
            else RenderingHints.VALUE_INTERPOLATION_BILINEAR)
        g.drawImage(image, (width - drawnWidth) / 2, (height - drawnHeight) / 2, drawnWidth, drawnHeight, null)
    }

    companion object {
        const val FRAME_WIDTH = 400
        const val FRAME_HEIGHT = 300
    }
}

/**
 * Live screen, the four buttons, status line and log of the device.
 * The device is polled once a second while the tool window is visible.
 */
private class DevicePanel(private val toolWindow: ToolWindow) : JPanel(BorderLayout()), Disposable {
    private val screen = ScreenView()
    private val status = JBLabel("Not connected")
    private val log = JTextArea().apply {
        isEditable = false
        font = JBUI.Fonts.create("Monospaced", 12)
    }
    private val poll: ScheduledFuture<*>
    private var follower: LogFollower? = null
    private var screenSupport = DeviceScreenSupport()
    private var connectedHost = ""
    private val simulatorRow = JPanel(FlowLayout(FlowLayout.CENTER, 4, 4))
    private var failures = 0
    private var skips = 0

    init {
        val buttons = JPanel(FlowLayout(FlowLayout.CENTER, 4, 4))
        for (button in DeviceButton.entries) {
            val control = JButton(button.name)
            control.toolTipText = "Click: short press. Shift+click: long press."
            control.addActionListener { event ->
                press(button, event.modifiers and ActionEvent.SHIFT_MASK != 0)
            }
            buttons.add(control)
        }
        val menu = JButton("Menu")
        menu.toolTipText = "Long press of BACK"
        menu.addActionListener { press(DeviceButton.BACK, true) }
        buttons.add(menu)
        val shot = JButton("Screenshot")
        shot.toolTipText = "Save the frame on the panel as a PNG and copy it to the clipboard"
        shot.addActionListener { saveScreenshot() }
        buttons.add(shot)
        val clear = JButton("Clear Log")
        clear.addActionListener { log.text = "" }
        buttons.add(clear)

        // The simulator's own: what the device does by itself, asked for now.
        simulatorRow.isVisible = false
        fun simulatorButton(text: String, tip: String, path: String) {
            val control = JButton(text)
            control.toolTipText = tip
            control.addActionListener { simulate(path) }
            simulatorRow.add(control)
        }
        simulatorButton("Background", "The application leaves the screen; it is loaded only for what comes to it",
            "background")
        simulatorButton("Foreground", "The application takes the screen again", "foreground")
        simulatorButton("Started", "Intent: the device was restarted", "intent?kind=started")
        simulatorButton("Time Changed", "Intent: the clock or the time zone changed", "intent?kind=time_changed")
        simulatorButton("Network Up", "Intent: the network is back", "intent?kind=network_up")
        simulatorButton("Alarm 0", "Alarm 0 comes now", "alarm?id=0")
        simulatorButton("+15 min", "The clock and every deadline move 15 minutes on", "forward?seconds=900")

        val top = JPanel(BorderLayout())
        screen.border = JBUI.Borders.customLine(JBColor.border())
        top.add(screen, BorderLayout.CENTER)
        val under = JPanel(BorderLayout())
        val rows = JPanel(BorderLayout())
        rows.add(buttons, BorderLayout.NORTH)
        rows.add(simulatorRow, BorderLayout.SOUTH)
        under.add(rows, BorderLayout.NORTH)
        status.border = JBUI.Borders.empty(2, 8)
        under.add(status, BorderLayout.SOUTH)
        top.add(under, BorderLayout.SOUTH)

        val splitter = JBSplitter(true, 0.6f)
        splitter.firstComponent = top
        splitter.secondComponent = JBScrollPane(log)
        add(splitter, BorderLayout.CENTER)

        poll = AppExecutorUtil.getAppScheduledExecutorService()
            .scheduleWithFixedDelay({ refresh() }, 0, 1, TimeUnit.SECONDS)
    }

    private fun client(): DeviceClient? {
        // A simulator that runs is what the window looks at; the device otherwise.
        val simulated = Simulator.activeHost
        val host = simulated ?: PerinkpheralSettings.get().deviceHost
        show { simulatorRow.isVisible = simulated != null }
        if (host != connectedHost) {
            connectedHost = host
            screenSupport = DeviceScreenSupport()
            follower = if (host.isBlank()) null else LogFollower(DeviceClient(host))
        }
        return if (host.isBlank()) null else DeviceClient(host)
    }

    private fun simulate(path: String) {
        val client = client() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                client.simulator(path)
                skips = 0
                refresh()
            } catch (error: Exception) {
                show { status.text = error.message ?: "Request failed" }
            }
        }
    }

    private fun press(button: DeviceButton, isLong: Boolean) {
        val client = client() ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                client.press(button, isLong)
                skips = 0
                refresh()
            } catch (error: Exception) {
                show { status.text = error.message ?: "Request failed" }
            }
        }
    }

    @Synchronized
    private fun refresh() {
        if (!toolWindow.isVisible) return
        // After a failure the device is asked less often: every 2, 4, 8 ... 16 seconds.
        if (skips > 0) {
            skips--
            return
        }
        val client = client()
        if (client == null) {
            show { status.text = "Device address is not set: Settings | Tools | perINKpheral" }
            return
        }
        try {
            val refreshed = DeviceRefresh(client::status, { follower?.next() }, client::screen,
                isSimulator = Simulator.activeHost != null, screenSupport = screenSupport)
            val frame = refreshed.screen.getOrNull()
            refreshed.updateScreen { image -> show { screen.frame = image } }
            // A device that started anew in between: its new log, from the beginning.
            val part = refreshed.logs.getOrNull()
            if (part != null && (part.text.isNotEmpty() || part.restarted)) {
                show {
                    if (part.restarted) log.append("--- restart ---\n")
                    log.append(part.text)
                    log.caretPosition = log.document.length
                }
            }
            val where = if (Simulator.activeHost != null) "Simulator" else connectedHost
            val firmware = refreshed.status.getOrNull()?.get("firmware")?.toString() ?: "connected"
            val pending = if (frame?.isPending == true) ", refresh pending" else ""
            val summary = if (frame == null) firmware else "$firmware, frame ${frame.number}$pending"
            val error = refreshed.error
            if (error != null) {
                failures = minOf(failures + 1, 4)
                skips = (1 shl failures) - 1
                show { status.text = "$where: $summary; ${error.message ?: error.javaClass.simpleName}" }
                return
            }
            failures = 0
            show { status.text = "$where: $summary" }
        } catch (error: Exception) {
            failures = minOf(failures + 1, 4)
            skips = (1 shl failures) - 1
            show { status.text = "$connectedHost: ${error.message ?: error.javaClass.simpleName}" }
        }
    }

    /** Saves the frame as shown, two screen pixels per panel pixel, and puts it on the clipboard. */
    private fun saveScreenshot() {
        val frame = screen.frame
        if (frame == null) {
            status.text = "No frame to save yet"
            return
        }
        val scale = 2
        val picture = BufferedImage(frame.width * scale, frame.height * scale, BufferedImage.TYPE_INT_RGB)
        val graphics = picture.createGraphics()
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
            RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
        graphics.drawImage(frame, 0, 0, picture.width, picture.height, null)
        graphics.dispose()
        // Straight to the system clipboard: the IDE's clipboard with history reads every
        // content as text, which a picture is not.
        Toolkit.getDefaultToolkit().systemClipboard.setContents(object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(flavor: DataFlavor) = flavor == DataFlavor.imageFlavor
            override fun getTransferData(flavor: DataFlavor): Any =
                if (flavor == DataFlavor.imageFlavor) picture else throw UnsupportedFlavorException(flavor)
        }, null)
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val descriptor = FileSaverDescriptor("Save Screenshot", "The frame on the panel", "png")
        val base = toolWindow.project.guessProjectDir()
        val chosen = FileChooserFactory.getInstance().createSaveFileDialog(descriptor, toolWindow.project)
            .save(base, "perinkpheral-$stamp.png") ?: run {
            status.text = "Screenshot copied to the clipboard"
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                ImageIO.write(picture, "png", chosen.file)
                LocalFileSystem.getInstance().refreshAndFindFileByIoFile(chosen.file)
                show { status.text = "Saved ${chosen.file.name}; copied to the clipboard" }
            } catch (error: Exception) {
                show { status.text = error.message ?: "Screenshot was not saved" }
            }
        }
    }

    private fun show(update: () -> Unit) {
        ApplicationManager.getApplication().invokeLater(update)
    }

    override fun dispose() {
        poll.cancel(false)
    }
}
