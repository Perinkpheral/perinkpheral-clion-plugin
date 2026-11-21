// SPDX-License-Identifier: MIT
package dev.perinkpheral.clion.run

import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.util.concurrency.AppExecutorUtil
import dev.perinkpheral.core.BuildError
import java.io.IOException
import java.io.OutputStream

/**
 * Work of the plugin's own shown in a Run console as a process would be: what it says is
 * the output, what it returns the exit code, and Stop cancels it - at the next step of the
 * work, at once for what was given to onStop (a request under way), and a program it starts
 * (the simulator), which is its child: its output comes here and Stop ends it, or keeps it
 * from starting.
 *
 * The network is the plugin's, in the IDE's own process: programs the IDE starts may not
 * be let into the local network by the system, and the device lives there.
 */
class TaskProcessHandler(private val work: TaskProcessHandler.() -> Int) : ProcessHandler() {
    @Volatile
    var isCancelled = false
        private set

    @Volatile
    private var child: Process? = null

    @Volatile
    private var detaching = false

    private val stops = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /** What Stop does at once, beside cancelling: cut a request the work waits on. */
    fun onStop(action: () -> Unit) {
        stops += action
        if (isCancelled) action()
    }

    override fun startNotify() {
        super.startNotify()
        AppExecutorUtil.getAppExecutorService().execute {
            val code = try {
                work()
            } catch (error: InterruptedException) {
                1
            } catch (error: Exception) {
                // Stopped, what the work stumbled on is the stop itself: nothing to say.
                if (!isCancelled) {
                    complain(when (error) {
                        is BuildError -> error.message ?: "The build failed."
                        is IOException -> error.message ?: error.javaClass.simpleName
                        else -> "${error.javaClass.simpleName}: ${error.message}"
                    })
                }
                1
            }
            if (detaching) notifyProcessDetached()
            else notifyProcessTerminated(if (isCancelled) 130 else code)
        }
    }

    fun say(text: String) = notifyTextAvailable(text + "\n", ProcessOutputTypes.STDOUT)

    fun complain(text: String) = notifyTextAvailable(text + "\n", ProcessOutputTypes.STDERR)

    /** Starts a program and passes its output on until it ends; returns its exit code. Not
     * started once Stop was pressed, and ended by a Stop that came while it was starting. */
    fun runChild(command: List<String>, onLine: (String) -> Unit = {}): Int {
        if (isCancelled) throw InterruptedException("cancelled")
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        child = process
        if (isCancelled) process.destroy()
        try {
            process.inputStream.bufferedReader().forEachLine {
                say(it)
                onLine(it)
            }
            return process.waitFor()
        } finally {
            child = null
        }
    }

    override fun destroyProcessImpl() {
        stop()
    }

    override fun detachProcessImpl() {
        detaching = true
        stop()
    }

    private fun stop() {
        isCancelled = true
        stops.forEach { it() }
        // The simulator stops as a device restarts: SIGTERM, and app_on_terminate() is called.
        child?.destroy()
    }

    override fun detachIsDefault(): Boolean = false

    override fun getProcessInput(): OutputStream? = null
}
