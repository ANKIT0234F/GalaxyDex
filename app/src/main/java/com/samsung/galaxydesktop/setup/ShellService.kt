package com.samsung.galaxydesktop.setup

import kotlin.system.exitProcess

/**
 * Started by Shizuku in a separate process running as the shell user, which is allowed to
 * write secure/global settings, grant permissions and set app-ops. Never instantiate directly.
 */
class ShellService : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun exec(command: String): String {
        val process = ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val code = process.waitFor()
        return "$code\n$output"
    }
}
