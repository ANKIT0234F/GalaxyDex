package com.samsung.galaxydesktop.setup;

// Runs inside Shizuku's process with shell (adb) privileges.
interface IShellService {
    // Transaction code reserved by Shizuku for tearing the service down.
    void destroy() = 16777114;

    // Runs `sh -c <command>`. Returns "<exit code>\n<combined stdout/stderr>".
    String exec(String command) = 1;
}
