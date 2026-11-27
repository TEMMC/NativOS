package com.mt.gnome

import java.io.File
import java.util.concurrent.TimeUnit

/** Runs Linux GUI commands in a private mount namespace without sharing the host PTY. */
internal object LinuxDistroLauncher {
    private fun root(id: String): String =
        if (id == "alpine") "/data/local/mt-os/distros/alpine" else "/data/local/mt-os/distros/$id"

    fun launchGnomeTerminal(id: String): Pair<Int, String> {
        val command = """
            set -e
            export HOME=/root USER=root LOGNAME=root
            export DISPLAY=:0 WAYLAND_DISPLAY=wayland-0 XDG_RUNTIME_DIR=/run/user/0
            mkdir -p /run/user/0 /tmp
            chmod 700 /run/user/0
            command -v gnome-terminal >/dev/null 2>&1 || { echo 'gnome-terminal executable not found'; exit 127; }
            if command -v dbus-run-session >/dev/null 2>&1; then
                nohup dbus-run-session -- gnome-terminal --disable-factory >/tmp/mt-gnome-terminal.log 2>&1 </dev/null &
            else
                nohup gnome-terminal --disable-factory >/tmp/mt-gnome-terminal.log 2>&1 </dev/null &
            fi
            launch_pid=${'$'}!
            sleep 1
            if ! kill -0 "${'$'}launch_pid" 2>/dev/null; then
                cat /tmp/mt-gnome-terminal.log 2>/dev/null || true
                exit 1
            fi
            echo 'GNOME Terminal process started'
        """.trimIndent()
        return runCommand(id, command, 45)
    }

    fun launchDesktopEntry(id: String, desktopId: String): Pair<Int, String> {
        val safe = desktopId.replace(Regex("[^A-Za-z0-9_.-]"), "")
        if (safe.isBlank()) return Pair(2, "Invalid desktop entry identifier")
        val command = """
            set -e
            export HOME=/root USER=root LOGNAME=root
            export DISPLAY=:0 WAYLAND_DISPLAY=wayland-0 XDG_RUNTIME_DIR=/run/user/0
            mkdir -p /run/user/0 /tmp
            chmod 700 /run/user/0
            command -v gtk-launch >/dev/null 2>&1 || { echo 'gtk-launch is not installed'; exit 127; }
            if command -v dbus-run-session >/dev/null 2>&1; then
                nohup dbus-run-session -- gtk-launch '$safe' >/tmp/mt-gnome-app.log 2>&1 </dev/null &
            else
                nohup gtk-launch '$safe' >/tmp/mt-gnome-app.log 2>&1 </dev/null &
            fi
            echo 'Linux application launch requested'
        """.trimIndent()
        return runCommand(id, command, 45)
    }

    fun runCommand(id: String, command: String, timeoutSeconds: Long = 1800, onOutput: (String) -> Unit = {}): Pair<Int, String> {
        val rootPath = root(id)
        val bbPath = "/data/adb/magisk/busybox"
        val inner = """
            set -eu
            BB="${'$'}1"; R="${'$'}2"; CMD="${'$'}3"
            "${'$'}BB" mount --make-rprivate /
            test -x "${'$'}R/bin/sh"
            mkdir -p "${'$'}R/dev" "${'$'}R/proc" "${'$'}R/sys" "${'$'}R/run" "${'$'}R/tmp"
            "${'$'}BB" mount -t tmpfs -o mode=755,nosuid tmpfs "${'$'}R/dev"
            for spec in "null 1 3" "zero 1 5" "full 1 7" "random 1 8" "urandom 1 9" "tty 5 0"; do
                set -- ${'$'}spec
                "${'$'}BB" mknod "${'$'}R/dev/${'$'}1" c "${'$'}2" "${'$'}3"
                chmod 666 "${'$'}R/dev/${'$'}1"
                "${'$'}BB" mount --bind "/dev/${'$'}1" "${'$'}R/dev/${'$'}1"
            done
            mkdir -p "${'$'}R/dev/pts" "${'$'}R/dev/shm"
            "${'$'}BB" mount -t devpts -o newinstance,ptmxmode=0666,mode=0620 devpts "${'$'}R/dev/pts"
            ln -sf pts/ptmx "${'$'}R/dev/ptmx"
            "${'$'}BB" mount -t tmpfs -o mode=1777,nosuid,nodev tmpfs "${'$'}R/dev/shm"
            ln -sf /proc/self/fd "${'$'}R/dev/fd"
            ln -sf /proc/self/fd/0 "${'$'}R/dev/stdin"
            ln -sf /proc/self/fd/1 "${'$'}R/dev/stdout"
            ln -sf /proc/self/fd/2 "${'$'}R/dev/stderr"
            "${'$'}BB" mount -t proc proc "${'$'}R/proc"
            "${'$'}BB" mount -t sysfs sysfs "${'$'}R/sys"
            exec chroot "${'$'}R" /bin/sh -c 'export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; export HOME=/root USER=root LOGNAME=root; exec /bin/sh -c "$1"' _ "${'$'}CMD"
        """.trimIndent()
        val script = """
            PATH=/system/bin:/system/xbin:/sbin:/data/adb/magisk:/vendor/bin:/product/bin:${'$'}PATH
            export PATH
            BB='$bbPath'
            if [ ! -x "${'$'}BB" ]; then BB=${'$'}(command -v busybox) || exit 126; fi
            exec unshare -m /system/bin/sh -c ${quote(inner)} _ "${'$'}BB" ${quote(rootPath)} ${quote(command)}
        """.trimIndent()
        return try {
            val suPath = resolveSu()
            try { onOutput("Requesting Magisk root authorization…") } catch (_: Exception) { }
            try { onOutput("Using root executable: $suPath") } catch (_: Exception) { }
            try { onOutput("Preparing isolated Linux mount namespace for $id…") } catch (_: Exception) { }
            val process = Runtime.getRuntime().exec(arrayOf(suPath, "-c", "exec 2>&1; $script"))
            try { onOutput("Root process started (PID ${process.pid()}); waiting for setup and package-manager output…") } catch (_: Exception) { }
            val output = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            synchronized(output) { output.append(line).append('\n') }
                            try { onOutput(line) } catch (_: Exception) { }
                        }
                    }
                } catch (_: Exception) { }
            }.apply { isDaemon = true; start() }
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                try { onOutput("ERROR: Linux command timed out after $timeoutSeconds seconds") } catch (_: Exception) { }
                process.destroy()
                if (process.isAlive) process.destroyForcibly()
                reader.join(500)
                return Pair(124, "Linux command timed out after $timeoutSeconds seconds")
            }
            reader.join(1500)
            val code = process.exitValue()
            try { onOutput("Linux command finished with exit code $code") } catch (_: Exception) { }
            Pair(code, synchronized(output) { output.toString() })
        } catch (e: Exception) {
            val message = e.message ?: e.toString()
            try { onOutput("ERROR: $message") } catch (_: Exception) { }
            Pair(126, message)
        }
    }

    private fun resolveSu(): String {
        val candidates = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su", "/data/adb/magisk/su", "/data/adb/ksu/bin/su")
        return candidates.firstOrNull { File(it).canExecute() } ?: "su"
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
}
}
