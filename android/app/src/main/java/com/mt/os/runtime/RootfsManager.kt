package com.mt.os.runtime

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages Linux rootfs downloads, extraction, and lifecycle.
 * Ported from DroidDesk's RootfsManager, adapted for MT-OS.
 */
class RootfsManager(private val context: Context) {

    companion object {
        private const val TAG = "MT-OS.RootfsManager"
        private const val BUFFER_SIZE = 8192
        private const val BUNDLED_ROOTFS_ASSET = "rootfs/mt-os-rootfs-arm64.tgz"
        private const val BUNDLED_DISTRO = "debian"

        val DISTRO_URLS = mapOf(
            "ubuntu" to "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz",
            "alpine" to "https://dl-cdn.alpinelinux.org/alpine/v3.20/releases/aarch64/alpine-minirootfs-3.20.0-aarch64.tar.gz"
        )
        val DISTRO_NAMES = mapOf(
            "ubuntu" to "Ubuntu 24.04 Base",
            "alpine" to "Alpine Linux 3.20"
        )
        const val DEFAULT_DISTRO = "ubuntu"
    }

    private val baseDir: File get() = context.filesDir
    private val rootfsDir: File get() = File(baseDir, "rootfs")
    private val downloadDir: File get() = File(baseDir, "downloads")
    private val configFile: File get() = File(baseDir, "distro.conf")
    private val setupCompleteFile: File get() = File(baseDir, "SETUP_COMPLETE")

    fun getInstalledDistro(): String =
        if (configFile.exists()) configFile.readText().trim() else ""

    fun getRootfsPath(): String = rootfsDir.absolutePath

    fun isRootfsReady(): Boolean =
        rootfsDir.exists() && File(rootfsDir, "bin").exists() &&
        File(rootfsDir, "usr").exists() && File(rootfsDir, "etc").exists()

    fun isSetupComplete(): Boolean = setupCompleteFile.exists()

    fun isFlatpakInstalled(): Boolean = File(rootfsDir, "usr/bin/flatpak").exists()

    /** Copy the production rootfs bundled in the signed APK into the staging area. */
    fun stageBundledRootfs(onProgress: (progress: Double, status: String) -> Unit): Boolean {
        val assetName = BUNDLED_ROOTFS_ASSET.substringAfterLast('/')
        val available = runCatching {
            context.assets.list("rootfs")?.contains(assetName) == true
        }.getOrDefault(false)
        if (!available) return false

        return try {
            downloadDir.mkdirs()
            val target = File(downloadDir, "$BUNDLED_DISTRO-rootfs.tar.gz")
            val temporary = File(downloadDir, "$BUNDLED_DISTRO-rootfs.tar.gz.part")
            onProgress(0.0, "Preparing bundled Linux system...")
            var copied = 0L
            var lastReportedPercent = -1
            val expected = runCatching {
                context.assets.openFd(BUNDLED_ROOTFS_ASSET).use { it.length }
            }.getOrDefault(-1L)
            context.assets.open(BUNDLED_ROOTFS_ASSET).use { input ->
                FileOutputStream(temporary, false).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                        if (expected > 0) {
                            val percent = ((copied * 100L) / expected).toInt().coerceIn(0, 100)
                            if (percent != lastReportedPercent) {
                                lastReportedPercent = percent
                                onProgress(percent / 100.0, "Preparing bundled Linux system...")
                            }
                        }
                    }
                    output.fd.sync()
                }
            }
            if (copied == 0L) throw IllegalStateException("Bundled rootfs is empty")
            if (target.exists() && !target.delete()) {
                throw IllegalStateException("Could not replace staged rootfs")
            }
            if (!temporary.renameTo(target)) {
                throw IllegalStateException("Could not finalize staged rootfs")
            }
            configFile.writeText(BUNDLED_DISTRO)
            onProgress(1.0, "Bundled Debian system ready")
            Log.i(TAG, "Staged bundled rootfs (${copied / (1024 * 1024)} MiB)")
            true
        } catch (error: Throwable) {
            File(downloadDir, "$BUNDLED_DISTRO-rootfs.tar.gz.part").delete()
            Log.e(TAG, "Could not stage bundled rootfs", error)
            false
        }
    }

    /**
     * Repair a partially provisioned Phosh installation. The phoc binary can be
     * unpacked before the remaining packages finish, so it is not a sufficient
     * readiness marker by itself. Without the touchscreen schema Phosh aborts
     * immediately and the X11 cursor visibly flickers in a restart loop.
     */
    /**
     * Validate the Phosh runtime prepared into the bundled Debian image.
     * First boot must never run apt or require network access.
     */
    /**
     * On the first launch, fetch missing Debian runtime dependencies if needed.
     * The bundled MT-Phoc/MT-Phosh executables are deliberately not replaced.
     */
    fun ensureFirstLaunchDependencies(chrootManager: ChrootManager): Boolean {
        val marker = File(baseDir, "FIRST_LAUNCH_DEPENDENCIES_READY")
        val schema = File(rootfsDir, "usr/share/glib-2.0/schemas/org.gnome.settings-daemon.peripherals.gschema.xml")
        val svgLoader = rootfsDir.walkTopDown().any { it.isFile && it.name == "libpixbufloader-svg.so" }
        val terminal = File(rootfsDir, "usr/bin/kgx").canExecute()
        val needRepair = !marker.isFile || !schema.isFile || !svgLoader || !terminal
        if (!needRepair) return true

        Log.i(TAG, "First-launch dependency check: downloading any missing Debian runtime packages")
        val result = chrootManager.execChroot(
            """
                set -e
                export DEBIAN_FRONTEND=noninteractive
                apt-get update
                apt-get install -y --no-install-recommends \
                    dbus dbus-x11 gnome-settings-daemon gsettings-desktop-schemas \
                    librsvg2-common libgdk-pixbuf2.0-bin libglib2.0-bin \
                    gnome-console adwaita-icon-theme hicolor-icon-theme \
                    xwayland squeekboard pipewire pipewire-pulse wireplumber feedbackd
                glib-compile-schemas /usr/share/glib-2.0/schemas
                SVG_LOADER="$(find /usr/lib -type f -name libpixbufloader-svg.so -print -quit)"
                test -n "$SVG_LOADER"
                GDK_QUERY="$(find /usr -type f -name gdk-pixbuf-query-loaders -perm /111 -print -quit)"
                if [ -n "$GDK_QUERY" ]; then
                    "$GDK_QUERY" > "$(dirname "$GDK_QUERY")/2.10.0/loaders.cache"
                fi
                test -s /usr/share/glib-2.0/schemas/org.gnome.settings-daemon.peripherals.gschema.xml
                test -x /usr/bin/kgx
            """.trimIndent()
        )
        if (result == 0) {
            marker.writeText("ready")
            Log.i(TAG, "First-launch dependencies are ready")
            return true
        }
        Log.e(TAG, "First-launch dependency download/install failed (exit $result)")
        return false
    }

    /** Validate Phosh runtime files after first-launch dependency repair. */
    fun ensurePhoshRuntime(chrootManager: ChrootManager): Boolean {
        val schema = File(rootfsDir, "usr/share/glib-2.0/schemas/org.gnome.settings-daemon.peripherals.gschema.xml")
        if (!schema.isFile) {
            Log.e(TAG, "Missing GNOME Settings Daemon schema: ${schema.absolutePath}")
            return false
        }
        val result = chrootManager.execChroot(
            """
                set -e
                SVG_LOADER="$(find /usr/lib -type f -name libpixbufloader-svg.so -print -quit)"
                test -n "$SVG_LOADER"
                glib-compile-schemas /usr/share/glib-2.0/schemas
                gtk-update-icon-cache -f -t /usr/share/icons/hicolor 2>/dev/null || true
                gtk-update-icon-cache -f -t /usr/share/icons/Adwaita 2>/dev/null || true
            """.trimIndent()
        )
        if (result != 0) Log.e(TAG, "Phosh runtime validation failed (exit $result)")
        return result == 0
    }
    /** Validate the preinstalled adaptive terminal without downloading packages. */
    fun ensureProfessionalTerminal(chrootManager: ChrootManager): Boolean {
        val console = File(rootfsDir, "usr/bin/kgx")
        if (console.isFile && console.canExecute()) return true
        Log.e(TAG, "Bundled Debian image is missing GNOME Console; rebuild the rootfs")
        return false
    }

    /** Add or repair the signed per-user Flathub remote used by GNOME Software. */
    fun ensureFlathub(chrootManager: ChrootManager): Boolean {
        if (!isFlatpakInstalled()) return false
        val result = chrootManager.execChroot(
            """
                mkdir -p /root/.gnupg
                chmod 0700 /root/.gnupg
                if flatpak --user remotes --columns=name 2>/dev/null | grep -Fxq flathub &&
                   test -s /root/.local/share/flatpak/repo/flathub.trustedkeys.gpg; then
                    exit 0
                fi
                flatpak --user remote-delete --force flathub 2>/dev/null || true
                timeout 60s flatpak --user remote-add flathub https://dl.flathub.org/repo/flathub.flatpakrepo
            """.trimIndent()
        )
        if (result == 0) {
            Log.i(TAG, "Flathub remote ready")
        } else {
            Log.w(TAG, "Could not configure Flathub (exit $result)")
        }
        return result == 0
    }

    fun downloadRootfs(
        distro: String = DEFAULT_DISTRO,
        onProgress: (progress: Double, status: String) -> Unit
    ) {
        try {
            val url = DISTRO_URLS[distro] ?: throw IllegalArgumentException("Unknown distro: $distro")
            val distroName = DISTRO_NAMES[distro] ?: distro
            downloadDir.mkdirs()
            val targetFile = File(downloadDir, "${distro}-rootfs.tar.gz")

            onProgress(0.0, "Connecting to download server...")
            Log.i(TAG, "Downloading $distroName from $url")

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true

            var downloadedBytes = 0L
            if (targetFile.exists()) {
                downloadedBytes = targetFile.length()
                connection.setRequestProperty("Range", "bytes=$downloadedBytes-")
            }
            connection.requestMethod = "GET"

            if (connection.responseCode == 416) {
                onProgress(1.0, "$distroName already downloaded")
                configFile.writeText(distro)
                return
            }
            if (connection.responseCode == 200 && downloadedBytes > 0) {
                targetFile.delete()
                downloadedBytes = 0L
            }

            val totalBytes = connection.contentLengthLong
            val expectedTotal = downloadedBytes + totalBytes
            if (totalBytes == 0L) {
                onProgress(1.0, "$distroName downloaded")
                configFile.writeText(distro)
                return
            }

            val buffer = ByteArray(BUFFER_SIZE)
            connection.inputStream.use { input ->
                FileOutputStream(targetFile, downloadedBytes > 0).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (expectedTotal > 0) {
                            val progress = downloadedBytes.toDouble() / expectedTotal
                            val dlMB = downloadedBytes / (1024 * 1024)
                            val totMB = expectedTotal / (1024 * 1024)
                            onProgress(progress, "Downloading: ${dlMB}MB / ${totMB}MB")
                        }
                    }
                }
            }
            connection.disconnect()
            configFile.writeText(distro)
            onProgress(1.0, "$distroName downloaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            onProgress(-1.0, "Download failed: ${e.message}")
        }
    }

    fun extractRootfs(onProgress: (progress: Double, status: String) -> Unit) {
        try {
            val distro = getInstalledDistro().ifEmpty { DEFAULT_DISTRO }
            val tarball = File(downloadDir, "${distro}-rootfs.tar.gz")
            if (!tarball.exists()) {
                onProgress(-1.0, "Rootfs tarball not found.")
                return
            }
            if (rootfsDir.exists()) rootfsDir.deleteRecursively()
            rootfsDir.mkdirs()

            onProgress(0.1, "Extracting Linux filesystem...")
            Log.i(TAG, "Extracting rootfs from ${tarball.absolutePath}")

            val process = ProcessBuilder("tar", "zxf", tarball.absolutePath, "-C", rootfsDir.absolutePath)
                .redirectErrorStream(true).start()
            val reader = process.inputStream.bufferedReader()
            var line: String?
            var lineCount = 0
            var lastLine = ""
            while (reader.readLine().also { line = it } != null) {
                lastLine = line!!
                lineCount++
                if (lineCount % 500 == 0) onProgress(0.1 + (lineCount % 5000) / 10000.0, "Extracting files...")
            }
            val exitCode = process.waitFor()
            val binDir = File(rootfsDir, "bin")
            if (exitCode != 0 && (!binDir.exists() || binDir.list()?.isEmpty() == true))
                throw RuntimeException("tar failed (code $exitCode): $lastLine")

            onProgress(0.7, "Configuring Linux environment...")
            configureRootfs()
            setupCompleteFile.writeText("done")
            tarball.delete()
            onProgress(1.0, "Linux filesystem ready")
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed: ${e.message}", e)
            onProgress(-1.0, "Extraction failed: ${e.message}")
        }
    }

    private fun configureRootfs() {
        // Debian may provide resolv.conf as a symlink into /run/systemd/resolve.
        // That target is absent during Android first boot, so replace the link
        // with a regular file before writing DNS settings.
        val etcDir = File(rootfsDir, "etc")
        if (java.nio.file.Files.isSymbolicLink(etcDir.toPath())) {
            java.nio.file.Files.delete(etcDir.toPath())
        }
        etcDir.mkdirs()

        val resolvConf = File(etcDir, "resolv.conf")
        if (java.nio.file.Files.isSymbolicLink(resolvConf.toPath())) {
            java.nio.file.Files.delete(resolvConf.toPath())
        } else if (resolvConf.exists() && !resolvConf.isFile) {
            resolvConf.delete()
        }
        resolvConf.writeText("nameserver 8.8.8.8\\nnameserver 8.8.4.4\\nnameserver 1.1.1.1\\n")
        File(rootfsDir, "etc/apt/apt.conf.d").mkdirs()
        File(rootfsDir, "etc/apt/apt.conf.d/99-disable-sandbox").writeText("APT::Sandbox::User \"root\";\n")
        File(rootfsDir, "etc/hostname").writeText("mtos\n")
        File(rootfsDir, "etc/hosts").apply {
            parentFile?.mkdirs()
            writeText("127.0.0.1 localhost\n127.0.0.1 mtos\n::1 localhost\n")
        }
        File(rootfsDir, "etc/profile.d/mtos.sh").apply {
            parentFile?.mkdirs()
            writeText("#!/bin/bash\nexport DISPLAY=:0\nexport XDG_RUNTIME_DIR=/tmp/runtime-root\nmkdir -p /tmp/runtime-root 2>/dev/null\n")
        }
        File(rootfsDir, "etc/sudoers.d/mtos").apply {
            parentFile?.mkdirs()
            writeText("Defaults !requiretty\nroot ALL=(ALL) NOPASSWD: ALL\n")
        }
        listOf("tmp", "tmp/runtime-root", "run", "var/run", "dev/shm").forEach { File(rootfsDir, it).mkdirs() }
    }

    fun installPhosh(chrootManager: ChrootManager, onProgress: (Double, String) -> Unit) {
        try {
            onProgress(0.0, "Clearing package locks...")
            try { chrootManager.execChroot("rm -f /var/lib/apt/lists/lock /var/cache/apt/archives/lock /var/lib/dpkg/lock*; dpkg --configure -a 2>/dev/null || true") } catch (_: Exception) {}

            onProgress(0.05, "Updating package lists...")
            chrootManager.execChroot("apt-get update -y")

            // dbus is CRITICAL - without it no session can start
            onProgress(0.10, "Installing D-Bus (required)...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends dbus dbus-x11 policykit-1 packagekit")

            onProgress(0.20, "Installing Phosh & Compositor...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends phoc phosh")

            onProgress(0.40, "Installing GUI Dependencies...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends squeekboard phosh-mobile-settings gnome-settings-daemon gnome-settings-daemon-common librsvg2-common gnome-console adwaita-icon-theme fonts-cantarell")

            onProgress(0.55, "Installing compilation tools...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends gcc libxcb1-dev libxcb-dri3-dev libc6-dev libvulkan1 vulkan-tools wget curl")

            onProgress(0.70, "Building universal hooks (Socket)...")
            chrootManager.execChroot("""
                bash -c "cat > /tmp/socket_hook.c << 'EOF'
#define _GNU_SOURCE
#include <sys/socket.h>
#include <sys/un.h>
#include <dlfcn.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <stddef.h>
int connect(int sockfd, const struct sockaddr *addr, socklen_t addrlen) {
    int (*real_connect)(int, const struct sockaddr *, socklen_t) = dlsym(RTLD_NEXT, \"connect\");
    if (addr && addr->sa_family == AF_UNIX) {
        struct sockaddr_un *un = (struct sockaddr_un *)addr;
        if (strstr(un->sun_path, \".X11-unix/X\")) {
            struct sockaddr_un abstract_addr;
            memset(&abstract_addr, 0, sizeof(abstract_addr));
            abstract_addr.sun_family = AF_UNIX;
            const char *tmpdir = getenv(\"TMPDIR\");
            if (!tmpdir) tmpdir = \"/tmp\";
            snprintf(abstract_addr.sun_path + 1, sizeof(abstract_addr.sun_path) - 1, \"%s/.X11-unix/X0\", tmpdir);
            socklen_t abs_len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(abstract_addr.sun_path + 1);
            return real_connect(sockfd, (struct sockaddr *)&abstract_addr, abs_len);
        }
    }
    return real_connect(sockfd, addr, addrlen);
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libsocket_hook.so /tmp/socket_hook.c -ldl"
            """.trimIndent())

            onProgress(0.85, "Building universal hooks (DRI3)...")
            chrootManager.execChroot("""
                bash -c "cat > /tmp/nodri3.c << 'EOF'
#include <xcb/xcb.h>
#include <dlfcn.h>
#include <stdio.h>
extern xcb_extension_t xcb_dri3_id;
const xcb_query_extension_reply_t *xcb_get_extension_data(xcb_connection_t *c, xcb_extension_t *ext) {
    const xcb_query_extension_reply_t *(*real_fn)(xcb_connection_t*, xcb_extension_t*) = dlsym(RTLD_NEXT, \"xcb_get_extension_data\");
    if (ext == &xcb_dri3_id) {
        const xcb_query_extension_reply_t *reply = real_fn(c, ext);
        if (reply && reply->present) {
            ((xcb_query_extension_reply_t *)reply)->present = 0;
        }
        return reply;
    }
    return real_fn(c, ext);
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libnodri3.so /tmp/nodri3.c -lxcb -ldl -lxcb-dri3"
            """.trimIndent())

            chrootManager.execChroot("""
                bash -c "cat > /tmp/close_range_compat.c << 'EOF'
#define _GNU_SOURCE
#include <errno.h>
int close_range(unsigned int first, unsigned int last, int flags) {
    (void) first; (void) last; (void) flags;
    errno = ENOSYS;
    return -1;
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libmtos-close-range.so /tmp/close_range_compat.c"
            """.trimIndent())

            onProgress(0.90, "Installing Built-in Apps & Store...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends software-properties-common nautilus gnome-calculator gnome-clocks megapixels gnome-software gnome-software-plugin-flatpak flatpak")

            onProgress(0.95, "Setting up Firefox...")
            chrootManager.execChroot("""
                add-apt-repository -y ppa:mozillateam/ppa
                printf '%s\\n' 'Package: *' 'Pin: release o=LP-PPA-mozillateam' 'Pin-Priority: 1001' '' 'Package: firefox' 'Pin: version 1:1snap1-0ubuntu2' 'Pin-Priority: -1' > /etc/apt/preferences.d/mozilla-firefox
                apt-get update
                DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends firefox
            """.trimIndent())

            onProgress(0.97, "Configuring Flathub...")
            ensureFlathub(chrootManager)

            onProgress(0.98, "Building icon caches...")
            chrootManager.execChroot("""
                bash -c "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && GDK_LOADER=${'$'}(find /usr/lib -name gdk-pixbuf-query-loaders | head -n 1) && ${'$'}GDK_LOADER > ${'$'}(dirname ${'$'}GDK_LOADER)/2.10.0/loaders.cache && gtk-update-icon-cache -f -t /usr/share/icons/hicolor && gtk-update-icon-cache -f -t /usr/share/icons/Adwaita && glib-compile-schemas /usr/share/glib-2.0/schemas"
            """.trimIndent())

            onProgress(1.0, "Phosh installed!")
        } catch (e: Exception) {
            Log.e(TAG, "Phosh install failed: ${e.message}", e)
            onProgress(-1.0, "Phosh install failed: ${e.message}")
        }
    }

    private fun calculateDirSize(dir: File): Long =
        if (!dir.exists()) 0 else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
}(find /usr/lib -type f -name libpixbufloader-svg.so -print -quit)
                test -n "${'
    }

    /** Validate the preinstalled adaptive terminal without downloading packages. */
    fun ensureProfessionalTerminal(chrootManager: ChrootManager): Boolean {
        val console = File(rootfsDir, "usr/bin/kgx")
        if (console.isFile && console.canExecute()) return true
        Log.e(TAG, "Bundled Debian image is missing GNOME Console; rebuild the rootfs")
        return false
    }

    /** Add or repair the signed per-user Flathub remote used by GNOME Software. */
    fun ensureFlathub(chrootManager: ChrootManager): Boolean {
        if (!isFlatpakInstalled()) return false
        val result = chrootManager.execChroot(
            """
                mkdir -p /root/.gnupg
                chmod 0700 /root/.gnupg
                if flatpak --user remotes --columns=name 2>/dev/null | grep -Fxq flathub &&
                   test -s /root/.local/share/flatpak/repo/flathub.trustedkeys.gpg; then
                    exit 0
                fi
                flatpak --user remote-delete --force flathub 2>/dev/null || true
                timeout 60s flatpak --user remote-add flathub https://dl.flathub.org/repo/flathub.flatpakrepo
            """.trimIndent()
        )
        if (result == 0) {
            Log.i(TAG, "Flathub remote ready")
        } else {
            Log.w(TAG, "Could not configure Flathub (exit $result)")
        }
        return result == 0
    }

    fun downloadRootfs(
        distro: String = DEFAULT_DISTRO,
        onProgress: (progress: Double, status: String) -> Unit
    ) {
        try {
            val url = DISTRO_URLS[distro] ?: throw IllegalArgumentException("Unknown distro: $distro")
            val distroName = DISTRO_NAMES[distro] ?: distro
            downloadDir.mkdirs()
            val targetFile = File(downloadDir, "${distro}-rootfs.tar.gz")

            onProgress(0.0, "Connecting to download server...")
            Log.i(TAG, "Downloading $distroName from $url")

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true

            var downloadedBytes = 0L
            if (targetFile.exists()) {
                downloadedBytes = targetFile.length()
                connection.setRequestProperty("Range", "bytes=$downloadedBytes-")
            }
            connection.requestMethod = "GET"

            if (connection.responseCode == 416) {
                onProgress(1.0, "$distroName already downloaded")
                configFile.writeText(distro)
                return
            }
            if (connection.responseCode == 200 && downloadedBytes > 0) {
                targetFile.delete()
                downloadedBytes = 0L
            }

            val totalBytes = connection.contentLengthLong
            val expectedTotal = downloadedBytes + totalBytes
            if (totalBytes == 0L) {
                onProgress(1.0, "$distroName downloaded")
                configFile.writeText(distro)
                return
            }

            val buffer = ByteArray(BUFFER_SIZE)
            connection.inputStream.use { input ->
                FileOutputStream(targetFile, downloadedBytes > 0).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (expectedTotal > 0) {
                            val progress = downloadedBytes.toDouble() / expectedTotal
                            val dlMB = downloadedBytes / (1024 * 1024)
                            val totMB = expectedTotal / (1024 * 1024)
                            onProgress(progress, "Downloading: ${dlMB}MB / ${totMB}MB")
                        }
                    }
                }
            }
            connection.disconnect()
            configFile.writeText(distro)
            onProgress(1.0, "$distroName downloaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            onProgress(-1.0, "Download failed: ${e.message}")
        }
    }

    fun extractRootfs(onProgress: (progress: Double, status: String) -> Unit) {
        try {
            val distro = getInstalledDistro().ifEmpty { DEFAULT_DISTRO }
            val tarball = File(downloadDir, "${distro}-rootfs.tar.gz")
            if (!tarball.exists()) {
                onProgress(-1.0, "Rootfs tarball not found.")
                return
            }
            if (rootfsDir.exists()) rootfsDir.deleteRecursively()
            rootfsDir.mkdirs()

            onProgress(0.1, "Extracting Linux filesystem...")
            Log.i(TAG, "Extracting rootfs from ${tarball.absolutePath}")

            val process = ProcessBuilder("tar", "zxf", tarball.absolutePath, "-C", rootfsDir.absolutePath)
                .redirectErrorStream(true).start()
            val reader = process.inputStream.bufferedReader()
            var line: String?
            var lineCount = 0
            var lastLine = ""
            while (reader.readLine().also { line = it } != null) {
                lastLine = line!!
                lineCount++
                if (lineCount % 500 == 0) onProgress(0.1 + (lineCount % 5000) / 10000.0, "Extracting files...")
            }
            val exitCode = process.waitFor()
            val binDir = File(rootfsDir, "bin")
            if (exitCode != 0 && (!binDir.exists() || binDir.list()?.isEmpty() == true))
                throw RuntimeException("tar failed (code $exitCode): $lastLine")

            onProgress(0.7, "Configuring Linux environment...")
            configureRootfs()
            setupCompleteFile.writeText("done")
            tarball.delete()
            onProgress(1.0, "Linux filesystem ready")
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed: ${e.message}", e)
            onProgress(-1.0, "Extraction failed: ${e.message}")
        }
    }

    private fun configureRootfs() {
        // Debian may provide resolv.conf as a symlink into /run/systemd/resolve.
        // That target is absent during Android first boot, so replace the link
        // with a regular file before writing DNS settings.
        val etcDir = File(rootfsDir, "etc")
        if (java.nio.file.Files.isSymbolicLink(etcDir.toPath())) {
            java.nio.file.Files.delete(etcDir.toPath())
        }
        etcDir.mkdirs()

        val resolvConf = File(etcDir, "resolv.conf")
        if (java.nio.file.Files.isSymbolicLink(resolvConf.toPath())) {
            java.nio.file.Files.delete(resolvConf.toPath())
        } else if (resolvConf.exists() && !resolvConf.isFile) {
            resolvConf.delete()
        }
        resolvConf.writeText("nameserver 8.8.8.8\\nnameserver 8.8.4.4\\nnameserver 1.1.1.1\\n")
        File(rootfsDir, "etc/apt/apt.conf.d").mkdirs()
        File(rootfsDir, "etc/apt/apt.conf.d/99-disable-sandbox").writeText("APT::Sandbox::User \"root\";\n")
        File(rootfsDir, "etc/hostname").writeText("mtos\n")
        File(rootfsDir, "etc/hosts").apply {
            parentFile?.mkdirs()
            writeText("127.0.0.1 localhost\n127.0.0.1 mtos\n::1 localhost\n")
        }
        File(rootfsDir, "etc/profile.d/mtos.sh").apply {
            parentFile?.mkdirs()
            writeText("#!/bin/bash\nexport DISPLAY=:0\nexport XDG_RUNTIME_DIR=/tmp/runtime-root\nmkdir -p /tmp/runtime-root 2>/dev/null\n")
        }
        File(rootfsDir, "etc/sudoers.d/mtos").apply {
            parentFile?.mkdirs()
            writeText("Defaults !requiretty\nroot ALL=(ALL) NOPASSWD: ALL\n")
        }
        listOf("tmp", "tmp/runtime-root", "run", "var/run", "dev/shm").forEach { File(rootfsDir, it).mkdirs() }
    }

    fun installPhosh(chrootManager: ChrootManager, onProgress: (Double, String) -> Unit) {
        try {
            onProgress(0.0, "Clearing package locks...")
            try { chrootManager.execChroot("rm -f /var/lib/apt/lists/lock /var/cache/apt/archives/lock /var/lib/dpkg/lock*; dpkg --configure -a 2>/dev/null || true") } catch (_: Exception) {}

            onProgress(0.05, "Updating package lists...")
            chrootManager.execChroot("apt-get update -y")

            // dbus is CRITICAL - without it no session can start
            onProgress(0.10, "Installing D-Bus (required)...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends dbus dbus-x11 policykit-1 packagekit")

            onProgress(0.20, "Installing Phosh & Compositor...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends phoc phosh")

            onProgress(0.40, "Installing GUI Dependencies...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends squeekboard phosh-mobile-settings gnome-settings-daemon gnome-settings-daemon-common librsvg2-common gnome-console adwaita-icon-theme fonts-cantarell")

            onProgress(0.55, "Installing compilation tools...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends gcc libxcb1-dev libxcb-dri3-dev libc6-dev libvulkan1 vulkan-tools wget curl")

            onProgress(0.70, "Building universal hooks (Socket)...")
            chrootManager.execChroot("""
                bash -c "cat > /tmp/socket_hook.c << 'EOF'
#define _GNU_SOURCE
#include <sys/socket.h>
#include <sys/un.h>
#include <dlfcn.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <stddef.h>
int connect(int sockfd, const struct sockaddr *addr, socklen_t addrlen) {
    int (*real_connect)(int, const struct sockaddr *, socklen_t) = dlsym(RTLD_NEXT, \"connect\");
    if (addr && addr->sa_family == AF_UNIX) {
        struct sockaddr_un *un = (struct sockaddr_un *)addr;
        if (strstr(un->sun_path, \".X11-unix/X\")) {
            struct sockaddr_un abstract_addr;
            memset(&abstract_addr, 0, sizeof(abstract_addr));
            abstract_addr.sun_family = AF_UNIX;
            const char *tmpdir = getenv(\"TMPDIR\");
            if (!tmpdir) tmpdir = \"/tmp\";
            snprintf(abstract_addr.sun_path + 1, sizeof(abstract_addr.sun_path) - 1, \"%s/.X11-unix/X0\", tmpdir);
            socklen_t abs_len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(abstract_addr.sun_path + 1);
            return real_connect(sockfd, (struct sockaddr *)&abstract_addr, abs_len);
        }
    }
    return real_connect(sockfd, addr, addrlen);
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libsocket_hook.so /tmp/socket_hook.c -ldl"
            """.trimIndent())

            onProgress(0.85, "Building universal hooks (DRI3)...")
            chrootManager.execChroot("""
                bash -c "cat > /tmp/nodri3.c << 'EOF'
#include <xcb/xcb.h>
#include <dlfcn.h>
#include <stdio.h>
extern xcb_extension_t xcb_dri3_id;
const xcb_query_extension_reply_t *xcb_get_extension_data(xcb_connection_t *c, xcb_extension_t *ext) {
    const xcb_query_extension_reply_t *(*real_fn)(xcb_connection_t*, xcb_extension_t*) = dlsym(RTLD_NEXT, \"xcb_get_extension_data\");
    if (ext == &xcb_dri3_id) {
        const xcb_query_extension_reply_t *reply = real_fn(c, ext);
        if (reply && reply->present) {
            ((xcb_query_extension_reply_t *)reply)->present = 0;
        }
        return reply;
    }
    return real_fn(c, ext);
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libnodri3.so /tmp/nodri3.c -lxcb -ldl -lxcb-dri3"
            """.trimIndent())

            chrootManager.execChroot("""
                bash -c "cat > /tmp/close_range_compat.c << 'EOF'
#define _GNU_SOURCE
#include <errno.h>
int close_range(unsigned int first, unsigned int last, int flags) {
    (void) first; (void) last; (void) flags;
    errno = ENOSYS;
    return -1;
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libmtos-close-range.so /tmp/close_range_compat.c"
            """.trimIndent())

            onProgress(0.90, "Installing Built-in Apps & Store...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends software-properties-common nautilus gnome-calculator gnome-clocks megapixels gnome-software gnome-software-plugin-flatpak flatpak")

            onProgress(0.95, "Setting up Firefox...")
            chrootManager.execChroot("""
                bash -c "add-apt-repository -y ppa:mozillateam/ppa && echo 'Package: *\nPin: release o=LP-PPA-mozillateam\nPin-Priority: 1001\n\nPackage: firefox\nPin: version 1:1snap1-0ubuntu2\nPin-Priority: -1' > /etc/apt/preferences.d/mozilla-firefox && apt-get update && DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends firefox"
            """.trimIndent())

            onProgress(0.97, "Configuring Flathub...")
            ensureFlathub(chrootManager)

            onProgress(0.98, "Building icon caches...")
            chrootManager.execChroot("""
                bash -c "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && GDK_LOADER=${'$'}(find /usr/lib -name gdk-pixbuf-query-loaders | head -n 1) && ${'$'}GDK_LOADER > ${'$'}(dirname ${'$'}GDK_LOADER)/2.10.0/loaders.cache && gtk-update-icon-cache -f -t /usr/share/icons/hicolor && gtk-update-icon-cache -f -t /usr/share/icons/Adwaita && glib-compile-schemas /usr/share/glib-2.0/schemas"
            """.trimIndent())

            onProgress(1.0, "Phosh installed!")
        } catch (e: Exception) {
            Log.e(TAG, "Phosh install failed: ${e.message}", e)
            onProgress(-1.0, "Phosh install failed: ${e.message}")
        }
    }

    private fun calculateDirSize(dir: File): Long =
        if (!dir.exists()) 0 else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
}SVG_LOADER" || {
                    echo "Missing librsvg GDK-Pixbuf loader"
                    exit 41
                }
                GDK_QUERY=${'
    }

    /** Validate the preinstalled adaptive terminal without downloading packages. */
    fun ensureProfessionalTerminal(chrootManager: ChrootManager): Boolean {
        val console = File(rootfsDir, "usr/bin/kgx")
        if (console.isFile && console.canExecute()) return true
        Log.e(TAG, "Bundled Debian image is missing GNOME Console; rebuild the rootfs")
        return false
    }

    /** Add or repair the signed per-user Flathub remote used by GNOME Software. */
    fun ensureFlathub(chrootManager: ChrootManager): Boolean {
        if (!isFlatpakInstalled()) return false
        val result = chrootManager.execChroot(
            """
                mkdir -p /root/.gnupg
                chmod 0700 /root/.gnupg
                if flatpak --user remotes --columns=name 2>/dev/null | grep -Fxq flathub &&
                   test -s /root/.local/share/flatpak/repo/flathub.trustedkeys.gpg; then
                    exit 0
                fi
                flatpak --user remote-delete --force flathub 2>/dev/null || true
                timeout 60s flatpak --user remote-add flathub https://dl.flathub.org/repo/flathub.flatpakrepo
            """.trimIndent()
        )
        if (result == 0) {
            Log.i(TAG, "Flathub remote ready")
        } else {
            Log.w(TAG, "Could not configure Flathub (exit $result)")
        }
        return result == 0
    }

    fun downloadRootfs(
        distro: String = DEFAULT_DISTRO,
        onProgress: (progress: Double, status: String) -> Unit
    ) {
        try {
            val url = DISTRO_URLS[distro] ?: throw IllegalArgumentException("Unknown distro: $distro")
            val distroName = DISTRO_NAMES[distro] ?: distro
            downloadDir.mkdirs()
            val targetFile = File(downloadDir, "${distro}-rootfs.tar.gz")

            onProgress(0.0, "Connecting to download server...")
            Log.i(TAG, "Downloading $distroName from $url")

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true

            var downloadedBytes = 0L
            if (targetFile.exists()) {
                downloadedBytes = targetFile.length()
                connection.setRequestProperty("Range", "bytes=$downloadedBytes-")
            }
            connection.requestMethod = "GET"

            if (connection.responseCode == 416) {
                onProgress(1.0, "$distroName already downloaded")
                configFile.writeText(distro)
                return
            }
            if (connection.responseCode == 200 && downloadedBytes > 0) {
                targetFile.delete()
                downloadedBytes = 0L
            }

            val totalBytes = connection.contentLengthLong
            val expectedTotal = downloadedBytes + totalBytes
            if (totalBytes == 0L) {
                onProgress(1.0, "$distroName downloaded")
                configFile.writeText(distro)
                return
            }

            val buffer = ByteArray(BUFFER_SIZE)
            connection.inputStream.use { input ->
                FileOutputStream(targetFile, downloadedBytes > 0).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (expectedTotal > 0) {
                            val progress = downloadedBytes.toDouble() / expectedTotal
                            val dlMB = downloadedBytes / (1024 * 1024)
                            val totMB = expectedTotal / (1024 * 1024)
                            onProgress(progress, "Downloading: ${dlMB}MB / ${totMB}MB")
                        }
                    }
                }
            }
            connection.disconnect()
            configFile.writeText(distro)
            onProgress(1.0, "$distroName downloaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            onProgress(-1.0, "Download failed: ${e.message}")
        }
    }

    fun extractRootfs(onProgress: (progress: Double, status: String) -> Unit) {
        try {
            val distro = getInstalledDistro().ifEmpty { DEFAULT_DISTRO }
            val tarball = File(downloadDir, "${distro}-rootfs.tar.gz")
            if (!tarball.exists()) {
                onProgress(-1.0, "Rootfs tarball not found.")
                return
            }
            if (rootfsDir.exists()) rootfsDir.deleteRecursively()
            rootfsDir.mkdirs()

            onProgress(0.1, "Extracting Linux filesystem...")
            Log.i(TAG, "Extracting rootfs from ${tarball.absolutePath}")

            val process = ProcessBuilder("tar", "zxf", tarball.absolutePath, "-C", rootfsDir.absolutePath)
                .redirectErrorStream(true).start()
            val reader = process.inputStream.bufferedReader()
            var line: String?
            var lineCount = 0
            var lastLine = ""
            while (reader.readLine().also { line = it } != null) {
                lastLine = line!!
                lineCount++
                if (lineCount % 500 == 0) onProgress(0.1 + (lineCount % 5000) / 10000.0, "Extracting files...")
            }
            val exitCode = process.waitFor()
            val binDir = File(rootfsDir, "bin")
            if (exitCode != 0 && (!binDir.exists() || binDir.list()?.isEmpty() == true))
                throw RuntimeException("tar failed (code $exitCode): $lastLine")

            onProgress(0.7, "Configuring Linux environment...")
            configureRootfs()
            setupCompleteFile.writeText("done")
            tarball.delete()
            onProgress(1.0, "Linux filesystem ready")
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed: ${e.message}", e)
            onProgress(-1.0, "Extraction failed: ${e.message}")
        }
    }

    private fun configureRootfs() {
        // Debian may provide resolv.conf as a symlink into /run/systemd/resolve.
        // That target is absent during Android first boot, so replace the link
        // with a regular file before writing DNS settings.
        val etcDir = File(rootfsDir, "etc")
        if (java.nio.file.Files.isSymbolicLink(etcDir.toPath())) {
            java.nio.file.Files.delete(etcDir.toPath())
        }
        etcDir.mkdirs()

        val resolvConf = File(etcDir, "resolv.conf")
        if (java.nio.file.Files.isSymbolicLink(resolvConf.toPath())) {
            java.nio.file.Files.delete(resolvConf.toPath())
        } else if (resolvConf.exists() && !resolvConf.isFile) {
            resolvConf.delete()
        }
        resolvConf.writeText("nameserver 8.8.8.8\\nnameserver 8.8.4.4\\nnameserver 1.1.1.1\\n")
        File(rootfsDir, "etc/apt/apt.conf.d").mkdirs()
        File(rootfsDir, "etc/apt/apt.conf.d/99-disable-sandbox").writeText("APT::Sandbox::User \"root\";\n")
        File(rootfsDir, "etc/hostname").writeText("mtos\n")
        File(rootfsDir, "etc/hosts").apply {
            parentFile?.mkdirs()
            writeText("127.0.0.1 localhost\n127.0.0.1 mtos\n::1 localhost\n")
        }
        File(rootfsDir, "etc/profile.d/mtos.sh").apply {
            parentFile?.mkdirs()
            writeText("#!/bin/bash\nexport DISPLAY=:0\nexport XDG_RUNTIME_DIR=/tmp/runtime-root\nmkdir -p /tmp/runtime-root 2>/dev/null\n")
        }
        File(rootfsDir, "etc/sudoers.d/mtos").apply {
            parentFile?.mkdirs()
            writeText("Defaults !requiretty\nroot ALL=(ALL) NOPASSWD: ALL\n")
        }
        listOf("tmp", "tmp/runtime-root", "run", "var/run", "dev/shm").forEach { File(rootfsDir, it).mkdirs() }
    }

    fun installPhosh(chrootManager: ChrootManager, onProgress: (Double, String) -> Unit) {
        try {
            onProgress(0.0, "Clearing package locks...")
            try { chrootManager.execChroot("rm -f /var/lib/apt/lists/lock /var/cache/apt/archives/lock /var/lib/dpkg/lock*; dpkg --configure -a 2>/dev/null || true") } catch (_: Exception) {}

            onProgress(0.05, "Updating package lists...")
            chrootManager.execChroot("apt-get update -y")

            // dbus is CRITICAL - without it no session can start
            onProgress(0.10, "Installing D-Bus (required)...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends dbus dbus-x11 policykit-1 packagekit")

            onProgress(0.20, "Installing Phosh & Compositor...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends phoc phosh")

            onProgress(0.40, "Installing GUI Dependencies...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends squeekboard phosh-mobile-settings gnome-settings-daemon gnome-settings-daemon-common librsvg2-common gnome-console adwaita-icon-theme fonts-cantarell")

            onProgress(0.55, "Installing compilation tools...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends gcc libxcb1-dev libxcb-dri3-dev libc6-dev libvulkan1 vulkan-tools wget curl")

            onProgress(0.70, "Building universal hooks (Socket)...")
            chrootManager.execChroot("""
                bash -c "cat > /tmp/socket_hook.c << 'EOF'
#define _GNU_SOURCE
#include <sys/socket.h>
#include <sys/un.h>
#include <dlfcn.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <stddef.h>
int connect(int sockfd, const struct sockaddr *addr, socklen_t addrlen) {
    int (*real_connect)(int, const struct sockaddr *, socklen_t) = dlsym(RTLD_NEXT, \"connect\");
    if (addr && addr->sa_family == AF_UNIX) {
        struct sockaddr_un *un = (struct sockaddr_un *)addr;
        if (strstr(un->sun_path, \".X11-unix/X\")) {
            struct sockaddr_un abstract_addr;
            memset(&abstract_addr, 0, sizeof(abstract_addr));
            abstract_addr.sun_family = AF_UNIX;
            const char *tmpdir = getenv(\"TMPDIR\");
            if (!tmpdir) tmpdir = \"/tmp\";
            snprintf(abstract_addr.sun_path + 1, sizeof(abstract_addr.sun_path) - 1, \"%s/.X11-unix/X0\", tmpdir);
            socklen_t abs_len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(abstract_addr.sun_path + 1);
            return real_connect(sockfd, (struct sockaddr *)&abstract_addr, abs_len);
        }
    }
    return real_connect(sockfd, addr, addrlen);
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libsocket_hook.so /tmp/socket_hook.c -ldl"
            """.trimIndent())

            onProgress(0.85, "Building universal hooks (DRI3)...")
            chrootManager.execChroot("""
                bash -c "cat > /tmp/nodri3.c << 'EOF'
#include <xcb/xcb.h>
#include <dlfcn.h>
#include <stdio.h>
extern xcb_extension_t xcb_dri3_id;
const xcb_query_extension_reply_t *xcb_get_extension_data(xcb_connection_t *c, xcb_extension_t *ext) {
    const xcb_query_extension_reply_t *(*real_fn)(xcb_connection_t*, xcb_extension_t*) = dlsym(RTLD_NEXT, \"xcb_get_extension_data\");
    if (ext == &xcb_dri3_id) {
        const xcb_query_extension_reply_t *reply = real_fn(c, ext);
        if (reply && reply->present) {
            ((xcb_query_extension_reply_t *)reply)->present = 0;
        }
        return reply;
    }
    return real_fn(c, ext);
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libnodri3.so /tmp/nodri3.c -lxcb -ldl -lxcb-dri3"
            """.trimIndent())

            chrootManager.execChroot("""
                bash -c "cat > /tmp/close_range_compat.c << 'EOF'
#define _GNU_SOURCE
#include <errno.h>
int close_range(unsigned int first, unsigned int last, int flags) {
    (void) first; (void) last; (void) flags;
    errno = ENOSYS;
    return -1;
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libmtos-close-range.so /tmp/close_range_compat.c"
            """.trimIndent())

            onProgress(0.90, "Installing Built-in Apps & Store...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends software-properties-common nautilus gnome-calculator gnome-clocks megapixels gnome-software gnome-software-plugin-flatpak flatpak")

            onProgress(0.95, "Setting up Firefox...")
            chrootManager.execChroot("""
                bash -c "add-apt-repository -y ppa:mozillateam/ppa && echo 'Package: *\nPin: release o=LP-PPA-mozillateam\nPin-Priority: 1001\n\nPackage: firefox\nPin: version 1:1snap1-0ubuntu2\nPin-Priority: -1' > /etc/apt/preferences.d/mozilla-firefox && apt-get update && DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends firefox"
            """.trimIndent())

            onProgress(0.97, "Configuring Flathub...")
            ensureFlathub(chrootManager)

            onProgress(0.98, "Building icon caches...")
            chrootManager.execChroot("""
                bash -c "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && GDK_LOADER=${'$'}(find /usr/lib -name gdk-pixbuf-query-loaders | head -n 1) && ${'$'}GDK_LOADER > ${'$'}(dirname ${'$'}GDK_LOADER)/2.10.0/loaders.cache && gtk-update-icon-cache -f -t /usr/share/icons/hicolor && gtk-update-icon-cache -f -t /usr/share/icons/Adwaita && glib-compile-schemas /usr/share/glib-2.0/schemas"
            """.trimIndent())

            onProgress(1.0, "Phosh installed!")
        } catch (e: Exception) {
            Log.e(TAG, "Phosh install failed: ${e.message}", e)
            onProgress(-1.0, "Phosh install failed: ${e.message}")
        }
    }

    private fun calculateDirSize(dir: File): Long =
        if (!dir.exists()) 0 else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
}(find /usr -type f -name gdk-pixbuf-query-loaders -perm /111 -print -quit)
                if [ -n "${'
    }

    /** Validate the preinstalled adaptive terminal without downloading packages. */
    fun ensureProfessionalTerminal(chrootManager: ChrootManager): Boolean {
        val console = File(rootfsDir, "usr/bin/kgx")
        if (console.isFile && console.canExecute()) return true
        Log.e(TAG, "Bundled Debian image is missing GNOME Console; rebuild the rootfs")
        return false
    }

    /** Add or repair the signed per-user Flathub remote used by GNOME Software. */
    fun ensureFlathub(chrootManager: ChrootManager): Boolean {
        if (!isFlatpakInstalled()) return false
        val result = chrootManager.execChroot(
            """
                mkdir -p /root/.gnupg
                chmod 0700 /root/.gnupg
                if flatpak --user remotes --columns=name 2>/dev/null | grep -Fxq flathub &&
                   test -s /root/.local/share/flatpak/repo/flathub.trustedkeys.gpg; then
                    exit 0
                fi
                flatpak --user remote-delete --force flathub 2>/dev/null || true
                timeout 60s flatpak --user remote-add flathub https://dl.flathub.org/repo/flathub.flatpakrepo
            """.trimIndent()
        )
        if (result == 0) {
            Log.i(TAG, "Flathub remote ready")
        } else {
            Log.w(TAG, "Could not configure Flathub (exit $result)")
        }
        return result == 0
    }

    fun downloadRootfs(
        distro: String = DEFAULT_DISTRO,
        onProgress: (progress: Double, status: String) -> Unit
    ) {
        try {
            val url = DISTRO_URLS[distro] ?: throw IllegalArgumentException("Unknown distro: $distro")
            val distroName = DISTRO_NAMES[distro] ?: distro
            downloadDir.mkdirs()
            val targetFile = File(downloadDir, "${distro}-rootfs.tar.gz")

            onProgress(0.0, "Connecting to download server...")
            Log.i(TAG, "Downloading $distroName from $url")

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true

            var downloadedBytes = 0L
            if (targetFile.exists()) {
                downloadedBytes = targetFile.length()
                connection.setRequestProperty("Range", "bytes=$downloadedBytes-")
            }
            connection.requestMethod = "GET"

            if (connection.responseCode == 416) {
                onProgress(1.0, "$distroName already downloaded")
                configFile.writeText(distro)
                return
            }
            if (connection.responseCode == 200 && downloadedBytes > 0) {
                targetFile.delete()
                downloadedBytes = 0L
            }

            val totalBytes = connection.contentLengthLong
            val expectedTotal = downloadedBytes + totalBytes
            if (totalBytes == 0L) {
                onProgress(1.0, "$distroName downloaded")
                configFile.writeText(distro)
                return
            }

            val buffer = ByteArray(BUFFER_SIZE)
            connection.inputStream.use { input ->
                FileOutputStream(targetFile, downloadedBytes > 0).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (expectedTotal > 0) {
                            val progress = downloadedBytes.toDouble() / expectedTotal
                            val dlMB = downloadedBytes / (1024 * 1024)
                            val totMB = expectedTotal / (1024 * 1024)
                            onProgress(progress, "Downloading: ${dlMB}MB / ${totMB}MB")
                        }
                    }
                }
            }
            connection.disconnect()
            configFile.writeText(distro)
            onProgress(1.0, "$distroName downloaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            onProgress(-1.0, "Download failed: ${e.message}")
        }
    }

    fun extractRootfs(onProgress: (progress: Double, status: String) -> Unit) {
        try {
            val distro = getInstalledDistro().ifEmpty { DEFAULT_DISTRO }
            val tarball = File(downloadDir, "${distro}-rootfs.tar.gz")
            if (!tarball.exists()) {
                onProgress(-1.0, "Rootfs tarball not found.")
                return
            }
            if (rootfsDir.exists()) rootfsDir.deleteRecursively()
            rootfsDir.mkdirs()

            onProgress(0.1, "Extracting Linux filesystem...")
            Log.i(TAG, "Extracting rootfs from ${tarball.absolutePath}")

            val process = ProcessBuilder("tar", "zxf", tarball.absolutePath, "-C", rootfsDir.absolutePath)
                .redirectErrorStream(true).start()
            val reader = process.inputStream.bufferedReader()
            var line: String?
            var lineCount = 0
            var lastLine = ""
            while (reader.readLine().also { line = it } != null) {
                lastLine = line!!
                lineCount++
                if (lineCount % 500 == 0) onProgress(0.1 + (lineCount % 5000) / 10000.0, "Extracting files...")
            }
            val exitCode = process.waitFor()
            val binDir = File(rootfsDir, "bin")
            if (exitCode != 0 && (!binDir.exists() || binDir.list()?.isEmpty() == true))
                throw RuntimeException("tar failed (code $exitCode): $lastLine")

            onProgress(0.7, "Configuring Linux environment...")
            configureRootfs()
            setupCompleteFile.writeText("done")
            tarball.delete()
            onProgress(1.0, "Linux filesystem ready")
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed: ${e.message}", e)
            onProgress(-1.0, "Extraction failed: ${e.message}")
        }
    }

    private fun configureRootfs() {
        // Debian may provide resolv.conf as a symlink into /run/systemd/resolve.
        // That target is absent during Android first boot, so replace the link
        // with a regular file before writing DNS settings.
        val etcDir = File(rootfsDir, "etc")
        if (java.nio.file.Files.isSymbolicLink(etcDir.toPath())) {
            java.nio.file.Files.delete(etcDir.toPath())
        }
        etcDir.mkdirs()

        val resolvConf = File(etcDir, "resolv.conf")
        if (java.nio.file.Files.isSymbolicLink(resolvConf.toPath())) {
            java.nio.file.Files.delete(resolvConf.toPath())
        } else if (resolvConf.exists() && !resolvConf.isFile) {
            resolvConf.delete()
        }
        resolvConf.writeText("nameserver 8.8.8.8\\nnameserver 8.8.4.4\\nnameserver 1.1.1.1\\n")
        File(rootfsDir, "etc/apt/apt.conf.d").mkdirs()
        File(rootfsDir, "etc/apt/apt.conf.d/99-disable-sandbox").writeText("APT::Sandbox::User \"root\";\n")
        File(rootfsDir, "etc/hostname").writeText("mtos\n")
        File(rootfsDir, "etc/hosts").apply {
            parentFile?.mkdirs()
            writeText("127.0.0.1 localhost\n127.0.0.1 mtos\n::1 localhost\n")
        }
        File(rootfsDir, "etc/profile.d/mtos.sh").apply {
            parentFile?.mkdirs()
            writeText("#!/bin/bash\nexport DISPLAY=:0\nexport XDG_RUNTIME_DIR=/tmp/runtime-root\nmkdir -p /tmp/runtime-root 2>/dev/null\n")
        }
        File(rootfsDir, "etc/sudoers.d/mtos").apply {
            parentFile?.mkdirs()
            writeText("Defaults !requiretty\nroot ALL=(ALL) NOPASSWD: ALL\n")
        }
        listOf("tmp", "tmp/runtime-root", "run", "var/run", "dev/shm").forEach { File(rootfsDir, it).mkdirs() }
    }

    fun installPhosh(chrootManager: ChrootManager, onProgress: (Double, String) -> Unit) {
        try {
            onProgress(0.0, "Clearing package locks...")
            try { chrootManager.execChroot("rm -f /var/lib/apt/lists/lock /var/cache/apt/archives/lock /var/lib/dpkg/lock*; dpkg --configure -a 2>/dev/null || true") } catch (_: Exception) {}

            onProgress(0.05, "Updating package lists...")
            chrootManager.execChroot("apt-get update -y")

            // dbus is CRITICAL - without it no session can start
            onProgress(0.10, "Installing D-Bus (required)...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends dbus dbus-x11 policykit-1 packagekit")

            onProgress(0.20, "Installing Phosh & Compositor...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends phoc phosh")

            onProgress(0.40, "Installing GUI Dependencies...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends squeekboard phosh-mobile-settings gnome-settings-daemon gnome-settings-daemon-common librsvg2-common gnome-console adwaita-icon-theme fonts-cantarell")

            onProgress(0.55, "Installing compilation tools...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends gcc libxcb1-dev libxcb-dri3-dev libc6-dev libvulkan1 vulkan-tools wget curl")

            onProgress(0.70, "Building universal hooks (Socket)...")
            chrootManager.execChroot("""
                bash -c "cat > /tmp/socket_hook.c << 'EOF'
#define _GNU_SOURCE
#include <sys/socket.h>
#include <sys/un.h>
#include <dlfcn.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <stddef.h>
int connect(int sockfd, const struct sockaddr *addr, socklen_t addrlen) {
    int (*real_connect)(int, const struct sockaddr *, socklen_t) = dlsym(RTLD_NEXT, \"connect\");
    if (addr && addr->sa_family == AF_UNIX) {
        struct sockaddr_un *un = (struct sockaddr_un *)addr;
        if (strstr(un->sun_path, \".X11-unix/X\")) {
            struct sockaddr_un abstract_addr;
            memset(&abstract_addr, 0, sizeof(abstract_addr));
            abstract_addr.sun_family = AF_UNIX;
            const char *tmpdir = getenv(\"TMPDIR\");
            if (!tmpdir) tmpdir = \"/tmp\";
            snprintf(abstract_addr.sun_path + 1, sizeof(abstract_addr.sun_path) - 1, \"%s/.X11-unix/X0\", tmpdir);
            socklen_t abs_len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(abstract_addr.sun_path + 1);
            return real_connect(sockfd, (struct sockaddr *)&abstract_addr, abs_len);
        }
    }
    return real_connect(sockfd, addr, addrlen);
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libsocket_hook.so /tmp/socket_hook.c -ldl"
            """.trimIndent())

            onProgress(0.85, "Building universal hooks (DRI3)...")
            chrootManager.execChroot("""
                bash -c "cat > /tmp/nodri3.c << 'EOF'
#include <xcb/xcb.h>
#include <dlfcn.h>
#include <stdio.h>
extern xcb_extension_t xcb_dri3_id;
const xcb_query_extension_reply_t *xcb_get_extension_data(xcb_connection_t *c, xcb_extension_t *ext) {
    const xcb_query_extension_reply_t *(*real_fn)(xcb_connection_t*, xcb_extension_t*) = dlsym(RTLD_NEXT, \"xcb_get_extension_data\");
    if (ext == &xcb_dri3_id) {
        const xcb_query_extension_reply_t *reply = real_fn(c, ext);
        if (reply && reply->present) {
            ((xcb_query_extension_reply_t *)reply)->present = 0;
        }
        return reply;
    }
    return real_fn(c, ext);
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libnodri3.so /tmp/nodri3.c -lxcb -ldl -lxcb-dri3"
            """.trimIndent())

            chrootManager.execChroot("""
                bash -c "cat > /tmp/close_range_compat.c << 'EOF'
#define _GNU_SOURCE
#include <errno.h>
int close_range(unsigned int first, unsigned int last, int flags) {
    (void) first; (void) last; (void) flags;
    errno = ENOSYS;
    return -1;
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libmtos-close-range.so /tmp/close_range_compat.c"
            """.trimIndent())

            onProgress(0.90, "Installing Built-in Apps & Store...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends software-properties-common nautilus gnome-calculator gnome-clocks megapixels gnome-software gnome-software-plugin-flatpak flatpak")

            onProgress(0.95, "Setting up Firefox...")
            chrootManager.execChroot("""
                bash -c "add-apt-repository -y ppa:mozillateam/ppa && echo 'Package: *\nPin: release o=LP-PPA-mozillateam\nPin-Priority: 1001\n\nPackage: firefox\nPin: version 1:1snap1-0ubuntu2\nPin-Priority: -1' > /etc/apt/preferences.d/mozilla-firefox && apt-get update && DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends firefox"
            """.trimIndent())

            onProgress(0.97, "Configuring Flathub...")
            ensureFlathub(chrootManager)

            onProgress(0.98, "Building icon caches...")
            chrootManager.execChroot("""
                bash -c "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && GDK_LOADER=${'$'}(find /usr/lib -name gdk-pixbuf-query-loaders | head -n 1) && ${'$'}GDK_LOADER > ${'$'}(dirname ${'$'}GDK_LOADER)/2.10.0/loaders.cache && gtk-update-icon-cache -f -t /usr/share/icons/hicolor && gtk-update-icon-cache -f -t /usr/share/icons/Adwaita && glib-compile-schemas /usr/share/glib-2.0/schemas"
            """.trimIndent())

            onProgress(1.0, "Phosh installed!")
        } catch (e: Exception) {
            Log.e(TAG, "Phosh install failed: ${e.message}", e)
            onProgress(-1.0, "Phosh install failed: ${e.message}")
        }
    }

    private fun calculateDirSize(dir: File): Long =
        if (!dir.exists()) 0 else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
}GDK_QUERY" ]; then
                    "${'
    }

    /** Validate the preinstalled adaptive terminal without downloading packages. */
    fun ensureProfessionalTerminal(chrootManager: ChrootManager): Boolean {
        val console = File(rootfsDir, "usr/bin/kgx")
        if (console.isFile && console.canExecute()) return true
        Log.e(TAG, "Bundled Debian image is missing GNOME Console; rebuild the rootfs")
        return false
    }

    /** Add or repair the signed per-user Flathub remote used by GNOME Software. */
    fun ensureFlathub(chrootManager: ChrootManager): Boolean {
        if (!isFlatpakInstalled()) return false
        val result = chrootManager.execChroot(
            """
                mkdir -p /root/.gnupg
                chmod 0700 /root/.gnupg
                if flatpak --user remotes --columns=name 2>/dev/null | grep -Fxq flathub &&
                   test -s /root/.local/share/flatpak/repo/flathub.trustedkeys.gpg; then
                    exit 0
                fi
                flatpak --user remote-delete --force flathub 2>/dev/null || true
                timeout 60s flatpak --user remote-add flathub https://dl.flathub.org/repo/flathub.flatpakrepo
            """.trimIndent()
        )
        if (result == 0) {
            Log.i(TAG, "Flathub remote ready")
        } else {
            Log.w(TAG, "Could not configure Flathub (exit $result)")
        }
        return result == 0
    }

    fun downloadRootfs(
        distro: String = DEFAULT_DISTRO,
        onProgress: (progress: Double, status: String) -> Unit
    ) {
        try {
            val url = DISTRO_URLS[distro] ?: throw IllegalArgumentException("Unknown distro: $distro")
            val distroName = DISTRO_NAMES[distro] ?: distro
            downloadDir.mkdirs()
            val targetFile = File(downloadDir, "${distro}-rootfs.tar.gz")

            onProgress(0.0, "Connecting to download server...")
            Log.i(TAG, "Downloading $distroName from $url")

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true

            var downloadedBytes = 0L
            if (targetFile.exists()) {
                downloadedBytes = targetFile.length()
                connection.setRequestProperty("Range", "bytes=$downloadedBytes-")
            }
            connection.requestMethod = "GET"

            if (connection.responseCode == 416) {
                onProgress(1.0, "$distroName already downloaded")
                configFile.writeText(distro)
                return
            }
            if (connection.responseCode == 200 && downloadedBytes > 0) {
                targetFile.delete()
                downloadedBytes = 0L
            }

            val totalBytes = connection.contentLengthLong
            val expectedTotal = downloadedBytes + totalBytes
            if (totalBytes == 0L) {
                onProgress(1.0, "$distroName downloaded")
                configFile.writeText(distro)
                return
            }

            val buffer = ByteArray(BUFFER_SIZE)
            connection.inputStream.use { input ->
                FileOutputStream(targetFile, downloadedBytes > 0).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (expectedTotal > 0) {
                            val progress = downloadedBytes.toDouble() / expectedTotal
                            val dlMB = downloadedBytes / (1024 * 1024)
                            val totMB = expectedTotal / (1024 * 1024)
                            onProgress(progress, "Downloading: ${dlMB}MB / ${totMB}MB")
                        }
                    }
                }
            }
            connection.disconnect()
            configFile.writeText(distro)
            onProgress(1.0, "$distroName downloaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            onProgress(-1.0, "Download failed: ${e.message}")
        }
    }

    fun extractRootfs(onProgress: (progress: Double, status: String) -> Unit) {
        try {
            val distro = getInstalledDistro().ifEmpty { DEFAULT_DISTRO }
            val tarball = File(downloadDir, "${distro}-rootfs.tar.gz")
            if (!tarball.exists()) {
                onProgress(-1.0, "Rootfs tarball not found.")
                return
            }
            if (rootfsDir.exists()) rootfsDir.deleteRecursively()
            rootfsDir.mkdirs()

            onProgress(0.1, "Extracting Linux filesystem...")
            Log.i(TAG, "Extracting rootfs from ${tarball.absolutePath}")

            val process = ProcessBuilder("tar", "zxf", tarball.absolutePath, "-C", rootfsDir.absolutePath)
                .redirectErrorStream(true).start()
            val reader = process.inputStream.bufferedReader()
            var line: String?
            var lineCount = 0
            var lastLine = ""
            while (reader.readLine().also { line = it } != null) {
                lastLine = line!!
                lineCount++
                if (lineCount % 500 == 0) onProgress(0.1 + (lineCount % 5000) / 10000.0, "Extracting files...")
            }
            val exitCode = process.waitFor()
            val binDir = File(rootfsDir, "bin")
            if (exitCode != 0 && (!binDir.exists() || binDir.list()?.isEmpty() == true))
                throw RuntimeException("tar failed (code $exitCode): $lastLine")

            onProgress(0.7, "Configuring Linux environment...")
            configureRootfs()
            setupCompleteFile.writeText("done")
            tarball.delete()
            onProgress(1.0, "Linux filesystem ready")
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed: ${e.message}", e)
            onProgress(-1.0, "Extraction failed: ${e.message}")
        }
    }

    private fun configureRootfs() {
        // Debian may provide resolv.conf as a symlink into /run/systemd/resolve.
        // That target is absent during Android first boot, so replace the link
        // with a regular file before writing DNS settings.
        val etcDir = File(rootfsDir, "etc")
        if (java.nio.file.Files.isSymbolicLink(etcDir.toPath())) {
            java.nio.file.Files.delete(etcDir.toPath())
        }
        etcDir.mkdirs()

        val resolvConf = File(etcDir, "resolv.conf")
        if (java.nio.file.Files.isSymbolicLink(resolvConf.toPath())) {
            java.nio.file.Files.delete(resolvConf.toPath())
        } else if (resolvConf.exists() && !resolvConf.isFile) {
            resolvConf.delete()
        }
        resolvConf.writeText("nameserver 8.8.8.8\\nnameserver 8.8.4.4\\nnameserver 1.1.1.1\\n")
        File(rootfsDir, "etc/apt/apt.conf.d").mkdirs()
        File(rootfsDir, "etc/apt/apt.conf.d/99-disable-sandbox").writeText("APT::Sandbox::User \"root\";\n")
        File(rootfsDir, "etc/hostname").writeText("mtos\n")
        File(rootfsDir, "etc/hosts").apply {
            parentFile?.mkdirs()
            writeText("127.0.0.1 localhost\n127.0.0.1 mtos\n::1 localhost\n")
        }
        File(rootfsDir, "etc/profile.d/mtos.sh").apply {
            parentFile?.mkdirs()
            writeText("#!/bin/bash\nexport DISPLAY=:0\nexport XDG_RUNTIME_DIR=/tmp/runtime-root\nmkdir -p /tmp/runtime-root 2>/dev/null\n")
        }
        File(rootfsDir, "etc/sudoers.d/mtos").apply {
            parentFile?.mkdirs()
            writeText("Defaults !requiretty\nroot ALL=(ALL) NOPASSWD: ALL\n")
        }
        listOf("tmp", "tmp/runtime-root", "run", "var/run", "dev/shm").forEach { File(rootfsDir, it).mkdirs() }
    }

    fun installPhosh(chrootManager: ChrootManager, onProgress: (Double, String) -> Unit) {
        try {
            onProgress(0.0, "Clearing package locks...")
            try { chrootManager.execChroot("rm -f /var/lib/apt/lists/lock /var/cache/apt/archives/lock /var/lib/dpkg/lock*; dpkg --configure -a 2>/dev/null || true") } catch (_: Exception) {}

            onProgress(0.05, "Updating package lists...")
            chrootManager.execChroot("apt-get update -y")

            // dbus is CRITICAL - without it no session can start
            onProgress(0.10, "Installing D-Bus (required)...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends dbus dbus-x11 policykit-1 packagekit")

            onProgress(0.20, "Installing Phosh & Compositor...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends phoc phosh")

            onProgress(0.40, "Installing GUI Dependencies...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends squeekboard phosh-mobile-settings gnome-settings-daemon gnome-settings-daemon-common librsvg2-common gnome-console adwaita-icon-theme fonts-cantarell")

            onProgress(0.55, "Installing compilation tools...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends gcc libxcb1-dev libxcb-dri3-dev libc6-dev libvulkan1 vulkan-tools wget curl")

            onProgress(0.70, "Building universal hooks (Socket)...")
            chrootManager.execChroot("""
                bash -c "cat > /tmp/socket_hook.c << 'EOF'
#define _GNU_SOURCE
#include <sys/socket.h>
#include <sys/un.h>
#include <dlfcn.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <stddef.h>
int connect(int sockfd, const struct sockaddr *addr, socklen_t addrlen) {
    int (*real_connect)(int, const struct sockaddr *, socklen_t) = dlsym(RTLD_NEXT, \"connect\");
    if (addr && addr->sa_family == AF_UNIX) {
        struct sockaddr_un *un = (struct sockaddr_un *)addr;
        if (strstr(un->sun_path, \".X11-unix/X\")) {
            struct sockaddr_un abstract_addr;
            memset(&abstract_addr, 0, sizeof(abstract_addr));
            abstract_addr.sun_family = AF_UNIX;
            const char *tmpdir = getenv(\"TMPDIR\");
            if (!tmpdir) tmpdir = \"/tmp\";
            snprintf(abstract_addr.sun_path + 1, sizeof(abstract_addr.sun_path) - 1, \"%s/.X11-unix/X0\", tmpdir);
            socklen_t abs_len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(abstract_addr.sun_path + 1);
            return real_connect(sockfd, (struct sockaddr *)&abstract_addr, abs_len);
        }
    }
    return real_connect(sockfd, addr, addrlen);
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libsocket_hook.so /tmp/socket_hook.c -ldl"
            """.trimIndent())

            onProgress(0.85, "Building universal hooks (DRI3)...")
            chrootManager.execChroot("""
                bash -c "cat > /tmp/nodri3.c << 'EOF'
#include <xcb/xcb.h>
#include <dlfcn.h>
#include <stdio.h>
extern xcb_extension_t xcb_dri3_id;
const xcb_query_extension_reply_t *xcb_get_extension_data(xcb_connection_t *c, xcb_extension_t *ext) {
    const xcb_query_extension_reply_t *(*real_fn)(xcb_connection_t*, xcb_extension_t*) = dlsym(RTLD_NEXT, \"xcb_get_extension_data\");
    if (ext == &xcb_dri3_id) {
        const xcb_query_extension_reply_t *reply = real_fn(c, ext);
        if (reply && reply->present) {
            ((xcb_query_extension_reply_t *)reply)->present = 0;
        }
        return reply;
    }
    return real_fn(c, ext);
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libnodri3.so /tmp/nodri3.c -lxcb -ldl -lxcb-dri3"
            """.trimIndent())

            chrootManager.execChroot("""
                bash -c "cat > /tmp/close_range_compat.c << 'EOF'
#define _GNU_SOURCE
#include <errno.h>
int close_range(unsigned int first, unsigned int last, int flags) {
    (void) first; (void) last; (void) flags;
    errno = ENOSYS;
    return -1;
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libmtos-close-range.so /tmp/close_range_compat.c"
            """.trimIndent())

            onProgress(0.90, "Installing Built-in Apps & Store...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends software-properties-common nautilus gnome-calculator gnome-clocks megapixels gnome-software gnome-software-plugin-flatpak flatpak")

            onProgress(0.95, "Setting up Firefox...")
            chrootManager.execChroot("""
                bash -c "add-apt-repository -y ppa:mozillateam/ppa && echo 'Package: *\nPin: release o=LP-PPA-mozillateam\nPin-Priority: 1001\n\nPackage: firefox\nPin: version 1:1snap1-0ubuntu2\nPin-Priority: -1' > /etc/apt/preferences.d/mozilla-firefox && apt-get update && DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends firefox"
            """.trimIndent())

            onProgress(0.97, "Configuring Flathub...")
            ensureFlathub(chrootManager)

            onProgress(0.98, "Building icon caches...")
            chrootManager.execChroot("""
                bash -c "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && GDK_LOADER=${'$'}(find /usr/lib -name gdk-pixbuf-query-loaders | head -n 1) && ${'$'}GDK_LOADER > ${'$'}(dirname ${'$'}GDK_LOADER)/2.10.0/loaders.cache && gtk-update-icon-cache -f -t /usr/share/icons/hicolor && gtk-update-icon-cache -f -t /usr/share/icons/Adwaita && glib-compile-schemas /usr/share/glib-2.0/schemas"
            """.trimIndent())

            onProgress(1.0, "Phosh installed!")
        } catch (e: Exception) {
            Log.e(TAG, "Phosh install failed: ${e.message}", e)
            onProgress(-1.0, "Phosh install failed: ${e.message}")
        }
    }

    private fun calculateDirSize(dir: File): Long =
        if (!dir.exists()) 0 else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
}GDK_QUERY" > "${'
    }

    /** Validate the preinstalled adaptive terminal without downloading packages. */
    fun ensureProfessionalTerminal(chrootManager: ChrootManager): Boolean {
        val console = File(rootfsDir, "usr/bin/kgx")
        if (console.isFile && console.canExecute()) return true
        Log.e(TAG, "Bundled Debian image is missing GNOME Console; rebuild the rootfs")
        return false
    }

    /** Add or repair the signed per-user Flathub remote used by GNOME Software. */
    fun ensureFlathub(chrootManager: ChrootManager): Boolean {
        if (!isFlatpakInstalled()) return false
        val result = chrootManager.execChroot(
            """
                mkdir -p /root/.gnupg
                chmod 0700 /root/.gnupg
                if flatpak --user remotes --columns=name 2>/dev/null | grep -Fxq flathub &&
                   test -s /root/.local/share/flatpak/repo/flathub.trustedkeys.gpg; then
                    exit 0
                fi
                flatpak --user remote-delete --force flathub 2>/dev/null || true
                timeout 60s flatpak --user remote-add flathub https://dl.flathub.org/repo/flathub.flatpakrepo
            """.trimIndent()
        )
        if (result == 0) {
            Log.i(TAG, "Flathub remote ready")
        } else {
            Log.w(TAG, "Could not configure Flathub (exit $result)")
        }
        return result == 0
    }

    fun downloadRootfs(
        distro: String = DEFAULT_DISTRO,
        onProgress: (progress: Double, status: String) -> Unit
    ) {
        try {
            val url = DISTRO_URLS[distro] ?: throw IllegalArgumentException("Unknown distro: $distro")
            val distroName = DISTRO_NAMES[distro] ?: distro
            downloadDir.mkdirs()
            val targetFile = File(downloadDir, "${distro}-rootfs.tar.gz")

            onProgress(0.0, "Connecting to download server...")
            Log.i(TAG, "Downloading $distroName from $url")

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true

            var downloadedBytes = 0L
            if (targetFile.exists()) {
                downloadedBytes = targetFile.length()
                connection.setRequestProperty("Range", "bytes=$downloadedBytes-")
            }
            connection.requestMethod = "GET"

            if (connection.responseCode == 416) {
                onProgress(1.0, "$distroName already downloaded")
                configFile.writeText(distro)
                return
            }
            if (connection.responseCode == 200 && downloadedBytes > 0) {
                targetFile.delete()
                downloadedBytes = 0L
            }

            val totalBytes = connection.contentLengthLong
            val expectedTotal = downloadedBytes + totalBytes
            if (totalBytes == 0L) {
                onProgress(1.0, "$distroName downloaded")
                configFile.writeText(distro)
                return
            }

            val buffer = ByteArray(BUFFER_SIZE)
            connection.inputStream.use { input ->
                FileOutputStream(targetFile, downloadedBytes > 0).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (expectedTotal > 0) {
                            val progress = downloadedBytes.toDouble() / expectedTotal
                            val dlMB = downloadedBytes / (1024 * 1024)
                            val totMB = expectedTotal / (1024 * 1024)
                            onProgress(progress, "Downloading: ${dlMB}MB / ${totMB}MB")
                        }
                    }
                }
            }
            connection.disconnect()
            configFile.writeText(distro)
            onProgress(1.0, "$distroName downloaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            onProgress(-1.0, "Download failed: ${e.message}")
        }
    }

    fun extractRootfs(onProgress: (progress: Double, status: String) -> Unit) {
        try {
            val distro = getInstalledDistro().ifEmpty { DEFAULT_DISTRO }
            val tarball = File(downloadDir, "${distro}-rootfs.tar.gz")
            if (!tarball.exists()) {
                onProgress(-1.0, "Rootfs tarball not found.")
                return
            }
            if (rootfsDir.exists()) rootfsDir.deleteRecursively()
            rootfsDir.mkdirs()

            onProgress(0.1, "Extracting Linux filesystem...")
            Log.i(TAG, "Extracting rootfs from ${tarball.absolutePath}")

            val process = ProcessBuilder("tar", "zxf", tarball.absolutePath, "-C", rootfsDir.absolutePath)
                .redirectErrorStream(true).start()
            val reader = process.inputStream.bufferedReader()
            var line: String?
            var lineCount = 0
            var lastLine = ""
            while (reader.readLine().also { line = it } != null) {
                lastLine = line!!
                lineCount++
                if (lineCount % 500 == 0) onProgress(0.1 + (lineCount % 5000) / 10000.0, "Extracting files...")
            }
            val exitCode = process.waitFor()
            val binDir = File(rootfsDir, "bin")
            if (exitCode != 0 && (!binDir.exists() || binDir.list()?.isEmpty() == true))
                throw RuntimeException("tar failed (code $exitCode): $lastLine")

            onProgress(0.7, "Configuring Linux environment...")
            configureRootfs()
            setupCompleteFile.writeText("done")
            tarball.delete()
            onProgress(1.0, "Linux filesystem ready")
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed: ${e.message}", e)
            onProgress(-1.0, "Extraction failed: ${e.message}")
        }
    }

    private fun configureRootfs() {
        // Debian may provide resolv.conf as a symlink into /run/systemd/resolve.
        // That target is absent during Android first boot, so replace the link
        // with a regular file before writing DNS settings.
        val etcDir = File(rootfsDir, "etc")
        if (java.nio.file.Files.isSymbolicLink(etcDir.toPath())) {
            java.nio.file.Files.delete(etcDir.toPath())
        }
        etcDir.mkdirs()

        val resolvConf = File(etcDir, "resolv.conf")
        if (java.nio.file.Files.isSymbolicLink(resolvConf.toPath())) {
            java.nio.file.Files.delete(resolvConf.toPath())
        } else if (resolvConf.exists() && !resolvConf.isFile) {
            resolvConf.delete()
        }
        resolvConf.writeText("nameserver 8.8.8.8\\nnameserver 8.8.4.4\\nnameserver 1.1.1.1\\n")
        File(rootfsDir, "etc/apt/apt.conf.d").mkdirs()
        File(rootfsDir, "etc/apt/apt.conf.d/99-disable-sandbox").writeText("APT::Sandbox::User \"root\";\n")
        File(rootfsDir, "etc/hostname").writeText("mtos\n")
        File(rootfsDir, "etc/hosts").apply {
            parentFile?.mkdirs()
            writeText("127.0.0.1 localhost\n127.0.0.1 mtos\n::1 localhost\n")
        }
        File(rootfsDir, "etc/profile.d/mtos.sh").apply {
            parentFile?.mkdirs()
            writeText("#!/bin/bash\nexport DISPLAY=:0\nexport XDG_RUNTIME_DIR=/tmp/runtime-root\nmkdir -p /tmp/runtime-root 2>/dev/null\n")
        }
        File(rootfsDir, "etc/sudoers.d/mtos").apply {
            parentFile?.mkdirs()
            writeText("Defaults !requiretty\nroot ALL=(ALL) NOPASSWD: ALL\n")
        }
        listOf("tmp", "tmp/runtime-root", "run", "var/run", "dev/shm").forEach { File(rootfsDir, it).mkdirs() }
    }

    fun installPhosh(chrootManager: ChrootManager, onProgress: (Double, String) -> Unit) {
        try {
            onProgress(0.0, "Clearing package locks...")
            try { chrootManager.execChroot("rm -f /var/lib/apt/lists/lock /var/cache/apt/archives/lock /var/lib/dpkg/lock*; dpkg --configure -a 2>/dev/null || true") } catch (_: Exception) {}

            onProgress(0.05, "Updating package lists...")
            chrootManager.execChroot("apt-get update -y")

            // dbus is CRITICAL - without it no session can start
            onProgress(0.10, "Installing D-Bus (required)...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends dbus dbus-x11 policykit-1 packagekit")

            onProgress(0.20, "Installing Phosh & Compositor...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends phoc phosh")

            onProgress(0.40, "Installing GUI Dependencies...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends squeekboard phosh-mobile-settings gnome-settings-daemon gnome-settings-daemon-common librsvg2-common gnome-console adwaita-icon-theme fonts-cantarell")

            onProgress(0.55, "Installing compilation tools...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends gcc libxcb1-dev libxcb-dri3-dev libc6-dev libvulkan1 vulkan-tools wget curl")

            onProgress(0.70, "Building universal hooks (Socket)...")
            chrootManager.execChroot("""
                bash -c "cat > /tmp/socket_hook.c << 'EOF'
#define _GNU_SOURCE
#include <sys/socket.h>
#include <sys/un.h>
#include <dlfcn.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <stddef.h>
int connect(int sockfd, const struct sockaddr *addr, socklen_t addrlen) {
    int (*real_connect)(int, const struct sockaddr *, socklen_t) = dlsym(RTLD_NEXT, \"connect\");
    if (addr && addr->sa_family == AF_UNIX) {
        struct sockaddr_un *un = (struct sockaddr_un *)addr;
        if (strstr(un->sun_path, \".X11-unix/X\")) {
            struct sockaddr_un abstract_addr;
            memset(&abstract_addr, 0, sizeof(abstract_addr));
            abstract_addr.sun_family = AF_UNIX;
            const char *tmpdir = getenv(\"TMPDIR\");
            if (!tmpdir) tmpdir = \"/tmp\";
            snprintf(abstract_addr.sun_path + 1, sizeof(abstract_addr.sun_path) - 1, \"%s/.X11-unix/X0\", tmpdir);
            socklen_t abs_len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(abstract_addr.sun_path + 1);
            return real_connect(sockfd, (struct sockaddr *)&abstract_addr, abs_len);
        }
    }
    return real_connect(sockfd, addr, addrlen);
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libsocket_hook.so /tmp/socket_hook.c -ldl"
            """.trimIndent())

            onProgress(0.85, "Building universal hooks (DRI3)...")
            chrootManager.execChroot("""
                bash -c "cat > /tmp/nodri3.c << 'EOF'
#include <xcb/xcb.h>
#include <dlfcn.h>
#include <stdio.h>
extern xcb_extension_t xcb_dri3_id;
const xcb_query_extension_reply_t *xcb_get_extension_data(xcb_connection_t *c, xcb_extension_t *ext) {
    const xcb_query_extension_reply_t *(*real_fn)(xcb_connection_t*, xcb_extension_t*) = dlsym(RTLD_NEXT, \"xcb_get_extension_data\");
    if (ext == &xcb_dri3_id) {
        const xcb_query_extension_reply_t *reply = real_fn(c, ext);
        if (reply && reply->present) {
            ((xcb_query_extension_reply_t *)reply)->present = 0;
        }
        return reply;
    }
    return real_fn(c, ext);
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libnodri3.so /tmp/nodri3.c -lxcb -ldl -lxcb-dri3"
            """.trimIndent())

            chrootManager.execChroot("""
                bash -c "cat > /tmp/close_range_compat.c << 'EOF'
#define _GNU_SOURCE
#include <errno.h>
int close_range(unsigned int first, unsigned int last, int flags) {
    (void) first; (void) last; (void) flags;
    errno = ENOSYS;
    return -1;
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libmtos-close-range.so /tmp/close_range_compat.c"
            """.trimIndent())

            onProgress(0.90, "Installing Built-in Apps & Store...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends software-properties-common nautilus gnome-calculator gnome-clocks megapixels gnome-software gnome-software-plugin-flatpak flatpak")

            onProgress(0.95, "Setting up Firefox...")
            chrootManager.execChroot("""
                bash -c "add-apt-repository -y ppa:mozillateam/ppa && echo 'Package: *\nPin: release o=LP-PPA-mozillateam\nPin-Priority: 1001\n\nPackage: firefox\nPin: version 1:1snap1-0ubuntu2\nPin-Priority: -1' > /etc/apt/preferences.d/mozilla-firefox && apt-get update && DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends firefox"
            """.trimIndent())

            onProgress(0.97, "Configuring Flathub...")
            ensureFlathub(chrootManager)

            onProgress(0.98, "Building icon caches...")
            chrootManager.execChroot("""
                bash -c "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && GDK_LOADER=${'$'}(find /usr/lib -name gdk-pixbuf-query-loaders | head -n 1) && ${'$'}GDK_LOADER > ${'$'}(dirname ${'$'}GDK_LOADER)/2.10.0/loaders.cache && gtk-update-icon-cache -f -t /usr/share/icons/hicolor && gtk-update-icon-cache -f -t /usr/share/icons/Adwaita && glib-compile-schemas /usr/share/glib-2.0/schemas"
            """.trimIndent())

            onProgress(1.0, "Phosh installed!")
        } catch (e: Exception) {
            Log.e(TAG, "Phosh install failed: ${e.message}", e)
            onProgress(-1.0, "Phosh install failed: ${e.message}")
        }
    }

    private fun calculateDirSize(dir: File): Long =
        if (!dir.exists()) 0 else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
}(dirname "${'
    }

    /** Validate the preinstalled adaptive terminal without downloading packages. */
    fun ensureProfessionalTerminal(chrootManager: ChrootManager): Boolean {
        val console = File(rootfsDir, "usr/bin/kgx")
        if (console.isFile && console.canExecute()) return true
        Log.e(TAG, "Bundled Debian image is missing GNOME Console; rebuild the rootfs")
        return false
    }

    /** Add or repair the signed per-user Flathub remote used by GNOME Software. */
    fun ensureFlathub(chrootManager: ChrootManager): Boolean {
        if (!isFlatpakInstalled()) return false
        val result = chrootManager.execChroot(
            """
                mkdir -p /root/.gnupg
                chmod 0700 /root/.gnupg
                if flatpak --user remotes --columns=name 2>/dev/null | grep -Fxq flathub &&
                   test -s /root/.local/share/flatpak/repo/flathub.trustedkeys.gpg; then
                    exit 0
                fi
                flatpak --user remote-delete --force flathub 2>/dev/null || true
                timeout 60s flatpak --user remote-add flathub https://dl.flathub.org/repo/flathub.flatpakrepo
            """.trimIndent()
        )
        if (result == 0) {
            Log.i(TAG, "Flathub remote ready")
        } else {
            Log.w(TAG, "Could not configure Flathub (exit $result)")
        }
        return result == 0
    }

    fun downloadRootfs(
        distro: String = DEFAULT_DISTRO,
        onProgress: (progress: Double, status: String) -> Unit
    ) {
        try {
            val url = DISTRO_URLS[distro] ?: throw IllegalArgumentException("Unknown distro: $distro")
            val distroName = DISTRO_NAMES[distro] ?: distro
            downloadDir.mkdirs()
            val targetFile = File(downloadDir, "${distro}-rootfs.tar.gz")

            onProgress(0.0, "Connecting to download server...")
            Log.i(TAG, "Downloading $distroName from $url")

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true

            var downloadedBytes = 0L
            if (targetFile.exists()) {
                downloadedBytes = targetFile.length()
                connection.setRequestProperty("Range", "bytes=$downloadedBytes-")
            }
            connection.requestMethod = "GET"

            if (connection.responseCode == 416) {
                onProgress(1.0, "$distroName already downloaded")
                configFile.writeText(distro)
                return
            }
            if (connection.responseCode == 200 && downloadedBytes > 0) {
                targetFile.delete()
                downloadedBytes = 0L
            }

            val totalBytes = connection.contentLengthLong
            val expectedTotal = downloadedBytes + totalBytes
            if (totalBytes == 0L) {
                onProgress(1.0, "$distroName downloaded")
                configFile.writeText(distro)
                return
            }

            val buffer = ByteArray(BUFFER_SIZE)
            connection.inputStream.use { input ->
                FileOutputStream(targetFile, downloadedBytes > 0).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (expectedTotal > 0) {
                            val progress = downloadedBytes.toDouble() / expectedTotal
                            val dlMB = downloadedBytes / (1024 * 1024)
                            val totMB = expectedTotal / (1024 * 1024)
                            onProgress(progress, "Downloading: ${dlMB}MB / ${totMB}MB")
                        }
                    }
                }
            }
            connection.disconnect()
            configFile.writeText(distro)
            onProgress(1.0, "$distroName downloaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            onProgress(-1.0, "Download failed: ${e.message}")
        }
    }

    fun extractRootfs(onProgress: (progress: Double, status: String) -> Unit) {
        try {
            val distro = getInstalledDistro().ifEmpty { DEFAULT_DISTRO }
            val tarball = File(downloadDir, "${distro}-rootfs.tar.gz")
            if (!tarball.exists()) {
                onProgress(-1.0, "Rootfs tarball not found.")
                return
            }
            if (rootfsDir.exists()) rootfsDir.deleteRecursively()
            rootfsDir.mkdirs()

            onProgress(0.1, "Extracting Linux filesystem...")
            Log.i(TAG, "Extracting rootfs from ${tarball.absolutePath}")

            val process = ProcessBuilder("tar", "zxf", tarball.absolutePath, "-C", rootfsDir.absolutePath)
                .redirectErrorStream(true).start()
            val reader = process.inputStream.bufferedReader()
            var line: String?
            var lineCount = 0
            var lastLine = ""
            while (reader.readLine().also { line = it } != null) {
                lastLine = line!!
                lineCount++
                if (lineCount % 500 == 0) onProgress(0.1 + (lineCount % 5000) / 10000.0, "Extracting files...")
            }
            val exitCode = process.waitFor()
            val binDir = File(rootfsDir, "bin")
            if (exitCode != 0 && (!binDir.exists() || binDir.list()?.isEmpty() == true))
                throw RuntimeException("tar failed (code $exitCode): $lastLine")

            onProgress(0.7, "Configuring Linux environment...")
            configureRootfs()
            setupCompleteFile.writeText("done")
            tarball.delete()
            onProgress(1.0, "Linux filesystem ready")
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed: ${e.message}", e)
            onProgress(-1.0, "Extraction failed: ${e.message}")
        }
    }

    private fun configureRootfs() {
        // Debian may provide resolv.conf as a symlink into /run/systemd/resolve.
        // That target is absent during Android first boot, so replace the link
        // with a regular file before writing DNS settings.
        val etcDir = File(rootfsDir, "etc")
        if (java.nio.file.Files.isSymbolicLink(etcDir.toPath())) {
            java.nio.file.Files.delete(etcDir.toPath())
        }
        etcDir.mkdirs()

        val resolvConf = File(etcDir, "resolv.conf")
        if (java.nio.file.Files.isSymbolicLink(resolvConf.toPath())) {
            java.nio.file.Files.delete(resolvConf.toPath())
        } else if (resolvConf.exists() && !resolvConf.isFile) {
            resolvConf.delete()
        }
        resolvConf.writeText("nameserver 8.8.8.8\\nnameserver 8.8.4.4\\nnameserver 1.1.1.1\\n")
        File(rootfsDir, "etc/apt/apt.conf.d").mkdirs()
        File(rootfsDir, "etc/apt/apt.conf.d/99-disable-sandbox").writeText("APT::Sandbox::User \"root\";\n")
        File(rootfsDir, "etc/hostname").writeText("mtos\n")
        File(rootfsDir, "etc/hosts").apply {
            parentFile?.mkdirs()
            writeText("127.0.0.1 localhost\n127.0.0.1 mtos\n::1 localhost\n")
        }
        File(rootfsDir, "etc/profile.d/mtos.sh").apply {
            parentFile?.mkdirs()
            writeText("#!/bin/bash\nexport DISPLAY=:0\nexport XDG_RUNTIME_DIR=/tmp/runtime-root\nmkdir -p /tmp/runtime-root 2>/dev/null\n")
        }
        File(rootfsDir, "etc/sudoers.d/mtos").apply {
            parentFile?.mkdirs()
            writeText("Defaults !requiretty\nroot ALL=(ALL) NOPASSWD: ALL\n")
        }
        listOf("tmp", "tmp/runtime-root", "run", "var/run", "dev/shm").forEach { File(rootfsDir, it).mkdirs() }
    }

    fun installPhosh(chrootManager: ChrootManager, onProgress: (Double, String) -> Unit) {
        try {
            onProgress(0.0, "Clearing package locks...")
            try { chrootManager.execChroot("rm -f /var/lib/apt/lists/lock /var/cache/apt/archives/lock /var/lib/dpkg/lock*; dpkg --configure -a 2>/dev/null || true") } catch (_: Exception) {}

            onProgress(0.05, "Updating package lists...")
            chrootManager.execChroot("apt-get update -y")

            // dbus is CRITICAL - without it no session can start
            onProgress(0.10, "Installing D-Bus (required)...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends dbus dbus-x11 policykit-1 packagekit")

            onProgress(0.20, "Installing Phosh & Compositor...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends phoc phosh")

            onProgress(0.40, "Installing GUI Dependencies...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends squeekboard phosh-mobile-settings gnome-settings-daemon gnome-settings-daemon-common librsvg2-common gnome-console adwaita-icon-theme fonts-cantarell")

            onProgress(0.55, "Installing compilation tools...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends gcc libxcb1-dev libxcb-dri3-dev libc6-dev libvulkan1 vulkan-tools wget curl")

            onProgress(0.70, "Building universal hooks (Socket)...")
            chrootManager.execChroot("""
                bash -c "cat > /tmp/socket_hook.c << 'EOF'
#define _GNU_SOURCE
#include <sys/socket.h>
#include <sys/un.h>
#include <dlfcn.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <stddef.h>
int connect(int sockfd, const struct sockaddr *addr, socklen_t addrlen) {
    int (*real_connect)(int, const struct sockaddr *, socklen_t) = dlsym(RTLD_NEXT, \"connect\");
    if (addr && addr->sa_family == AF_UNIX) {
        struct sockaddr_un *un = (struct sockaddr_un *)addr;
        if (strstr(un->sun_path, \".X11-unix/X\")) {
            struct sockaddr_un abstract_addr;
            memset(&abstract_addr, 0, sizeof(abstract_addr));
            abstract_addr.sun_family = AF_UNIX;
            const char *tmpdir = getenv(\"TMPDIR\");
            if (!tmpdir) tmpdir = \"/tmp\";
            snprintf(abstract_addr.sun_path + 1, sizeof(abstract_addr.sun_path) - 1, \"%s/.X11-unix/X0\", tmpdir);
            socklen_t abs_len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(abstract_addr.sun_path + 1);
            return real_connect(sockfd, (struct sockaddr *)&abstract_addr, abs_len);
        }
    }
    return real_connect(sockfd, addr, addrlen);
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libsocket_hook.so /tmp/socket_hook.c -ldl"
            """.trimIndent())

            onProgress(0.85, "Building universal hooks (DRI3)...")
            chrootManager.execChroot("""
                bash -c "cat > /tmp/nodri3.c << 'EOF'
#include <xcb/xcb.h>
#include <dlfcn.h>
#include <stdio.h>
extern xcb_extension_t xcb_dri3_id;
const xcb_query_extension_reply_t *xcb_get_extension_data(xcb_connection_t *c, xcb_extension_t *ext) {
    const xcb_query_extension_reply_t *(*real_fn)(xcb_connection_t*, xcb_extension_t*) = dlsym(RTLD_NEXT, \"xcb_get_extension_data\");
    if (ext == &xcb_dri3_id) {
        const xcb_query_extension_reply_t *reply = real_fn(c, ext);
        if (reply && reply->present) {
            ((xcb_query_extension_reply_t *)reply)->present = 0;
        }
        return reply;
    }
    return real_fn(c, ext);
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libnodri3.so /tmp/nodri3.c -lxcb -ldl -lxcb-dri3"
            """.trimIndent())

            chrootManager.execChroot("""
                bash -c "cat > /tmp/close_range_compat.c << 'EOF'
#define _GNU_SOURCE
#include <errno.h>
int close_range(unsigned int first, unsigned int last, int flags) {
    (void) first; (void) last; (void) flags;
    errno = ENOSYS;
    return -1;
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libmtos-close-range.so /tmp/close_range_compat.c"
            """.trimIndent())

            onProgress(0.90, "Installing Built-in Apps & Store...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends software-properties-common nautilus gnome-calculator gnome-clocks megapixels gnome-software gnome-software-plugin-flatpak flatpak")

            onProgress(0.95, "Setting up Firefox...")
            chrootManager.execChroot("""
                bash -c "add-apt-repository -y ppa:mozillateam/ppa && echo 'Package: *\nPin: release o=LP-PPA-mozillateam\nPin-Priority: 1001\n\nPackage: firefox\nPin: version 1:1snap1-0ubuntu2\nPin-Priority: -1' > /etc/apt/preferences.d/mozilla-firefox && apt-get update && DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends firefox"
            """.trimIndent())

            onProgress(0.97, "Configuring Flathub...")
            ensureFlathub(chrootManager)

            onProgress(0.98, "Building icon caches...")
            chrootManager.execChroot("""
                bash -c "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && GDK_LOADER=${'$'}(find /usr/lib -name gdk-pixbuf-query-loaders | head -n 1) && ${'$'}GDK_LOADER > ${'$'}(dirname ${'$'}GDK_LOADER)/2.10.0/loaders.cache && gtk-update-icon-cache -f -t /usr/share/icons/hicolor && gtk-update-icon-cache -f -t /usr/share/icons/Adwaita && glib-compile-schemas /usr/share/glib-2.0/schemas"
            """.trimIndent())

            onProgress(1.0, "Phosh installed!")
        } catch (e: Exception) {
            Log.e(TAG, "Phosh install failed: ${e.message}", e)
            onProgress(-1.0, "Phosh install failed: ${e.message}")
        }
    }

    private fun calculateDirSize(dir: File): Long =
        if (!dir.exists()) 0 else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
}GDK_QUERY")/2.10.0/loaders.cache"
                fi
                glib-compile-schemas /usr/share/glib-2.0/schemas
                gtk-update-icon-cache -f -t /usr/share/icons/hicolor 2>/dev/null || true
                gtk-update-icon-cache -f -t /usr/share/icons/Adwaita 2>/dev/null || true
            """.trimIndent()
        )
        if (result != 0) Log.e(TAG, "Offline Phosh runtime validation/repair failed (exit $result)")
        return result == 0
    }

    /** Validate the preinstalled adaptive terminal without downloading packages. */
    fun ensureProfessionalTerminal(chrootManager: ChrootManager): Boolean {
        val console = File(rootfsDir, "usr/bin/kgx")
        if (console.isFile && console.canExecute()) return true
        Log.e(TAG, "Bundled Debian image is missing GNOME Console; rebuild the rootfs")
        return false
    }

    /** Add or repair the signed per-user Flathub remote used by GNOME Software. */
    fun ensureFlathub(chrootManager: ChrootManager): Boolean {
        if (!isFlatpakInstalled()) return false
        val result = chrootManager.execChroot(
            """
                mkdir -p /root/.gnupg
                chmod 0700 /root/.gnupg
                if flatpak --user remotes --columns=name 2>/dev/null | grep -Fxq flathub &&
                   test -s /root/.local/share/flatpak/repo/flathub.trustedkeys.gpg; then
                    exit 0
                fi
                flatpak --user remote-delete --force flathub 2>/dev/null || true
                timeout 60s flatpak --user remote-add flathub https://dl.flathub.org/repo/flathub.flatpakrepo
            """.trimIndent()
        )
        if (result == 0) {
            Log.i(TAG, "Flathub remote ready")
        } else {
            Log.w(TAG, "Could not configure Flathub (exit $result)")
        }
        return result == 0
    }

    fun downloadRootfs(
        distro: String = DEFAULT_DISTRO,
        onProgress: (progress: Double, status: String) -> Unit
    ) {
        try {
            val url = DISTRO_URLS[distro] ?: throw IllegalArgumentException("Unknown distro: $distro")
            val distroName = DISTRO_NAMES[distro] ?: distro
            downloadDir.mkdirs()
            val targetFile = File(downloadDir, "${distro}-rootfs.tar.gz")

            onProgress(0.0, "Connecting to download server...")
            Log.i(TAG, "Downloading $distroName from $url")

            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.instanceFollowRedirects = true

            var downloadedBytes = 0L
            if (targetFile.exists()) {
                downloadedBytes = targetFile.length()
                connection.setRequestProperty("Range", "bytes=$downloadedBytes-")
            }
            connection.requestMethod = "GET"

            if (connection.responseCode == 416) {
                onProgress(1.0, "$distroName already downloaded")
                configFile.writeText(distro)
                return
            }
            if (connection.responseCode == 200 && downloadedBytes > 0) {
                targetFile.delete()
                downloadedBytes = 0L
            }

            val totalBytes = connection.contentLengthLong
            val expectedTotal = downloadedBytes + totalBytes
            if (totalBytes == 0L) {
                onProgress(1.0, "$distroName downloaded")
                configFile.writeText(distro)
                return
            }

            val buffer = ByteArray(BUFFER_SIZE)
            connection.inputStream.use { input ->
                FileOutputStream(targetFile, downloadedBytes > 0).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (expectedTotal > 0) {
                            val progress = downloadedBytes.toDouble() / expectedTotal
                            val dlMB = downloadedBytes / (1024 * 1024)
                            val totMB = expectedTotal / (1024 * 1024)
                            onProgress(progress, "Downloading: ${dlMB}MB / ${totMB}MB")
                        }
                    }
                }
            }
            connection.disconnect()
            configFile.writeText(distro)
            onProgress(1.0, "$distroName downloaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Download failed: ${e.message}", e)
            onProgress(-1.0, "Download failed: ${e.message}")
        }
    }

    fun extractRootfs(onProgress: (progress: Double, status: String) -> Unit) {
        try {
            val distro = getInstalledDistro().ifEmpty { DEFAULT_DISTRO }
            val tarball = File(downloadDir, "${distro}-rootfs.tar.gz")
            if (!tarball.exists()) {
                onProgress(-1.0, "Rootfs tarball not found.")
                return
            }
            if (rootfsDir.exists()) rootfsDir.deleteRecursively()
            rootfsDir.mkdirs()

            onProgress(0.1, "Extracting Linux filesystem...")
            Log.i(TAG, "Extracting rootfs from ${tarball.absolutePath}")

            val process = ProcessBuilder("tar", "zxf", tarball.absolutePath, "-C", rootfsDir.absolutePath)
                .redirectErrorStream(true).start()
            val reader = process.inputStream.bufferedReader()
            var line: String?
            var lineCount = 0
            var lastLine = ""
            while (reader.readLine().also { line = it } != null) {
                lastLine = line!!
                lineCount++
                if (lineCount % 500 == 0) onProgress(0.1 + (lineCount % 5000) / 10000.0, "Extracting files...")
            }
            val exitCode = process.waitFor()
            val binDir = File(rootfsDir, "bin")
            if (exitCode != 0 && (!binDir.exists() || binDir.list()?.isEmpty() == true))
                throw RuntimeException("tar failed (code $exitCode): $lastLine")

            onProgress(0.7, "Configuring Linux environment...")
            configureRootfs()
            setupCompleteFile.writeText("done")
            tarball.delete()
            onProgress(1.0, "Linux filesystem ready")
        } catch (e: Exception) {
            Log.e(TAG, "Extraction failed: ${e.message}", e)
            onProgress(-1.0, "Extraction failed: ${e.message}")
        }
    }

    private fun configureRootfs() {
        // Debian may provide resolv.conf as a symlink into /run/systemd/resolve.
        // That target is absent during Android first boot, so replace the link
        // with a regular file before writing DNS settings.
        val etcDir = File(rootfsDir, "etc")
        if (java.nio.file.Files.isSymbolicLink(etcDir.toPath())) {
            java.nio.file.Files.delete(etcDir.toPath())
        }
        etcDir.mkdirs()

        val resolvConf = File(etcDir, "resolv.conf")
        if (java.nio.file.Files.isSymbolicLink(resolvConf.toPath())) {
            java.nio.file.Files.delete(resolvConf.toPath())
        } else if (resolvConf.exists() && !resolvConf.isFile) {
            resolvConf.delete()
        }
        resolvConf.writeText("nameserver 8.8.8.8\\nnameserver 8.8.4.4\\nnameserver 1.1.1.1\\n")
        File(rootfsDir, "etc/apt/apt.conf.d").mkdirs()
        File(rootfsDir, "etc/apt/apt.conf.d/99-disable-sandbox").writeText("APT::Sandbox::User \"root\";\n")
        File(rootfsDir, "etc/hostname").writeText("mtos\n")
        File(rootfsDir, "etc/hosts").apply {
            parentFile?.mkdirs()
            writeText("127.0.0.1 localhost\n127.0.0.1 mtos\n::1 localhost\n")
        }
        File(rootfsDir, "etc/profile.d/mtos.sh").apply {
            parentFile?.mkdirs()
            writeText("#!/bin/bash\nexport DISPLAY=:0\nexport XDG_RUNTIME_DIR=/tmp/runtime-root\nmkdir -p /tmp/runtime-root 2>/dev/null\n")
        }
        File(rootfsDir, "etc/sudoers.d/mtos").apply {
            parentFile?.mkdirs()
            writeText("Defaults !requiretty\nroot ALL=(ALL) NOPASSWD: ALL\n")
        }
        listOf("tmp", "tmp/runtime-root", "run", "var/run", "dev/shm").forEach { File(rootfsDir, it).mkdirs() }
    }

    fun installPhosh(chrootManager: ChrootManager, onProgress: (Double, String) -> Unit) {
        try {
            onProgress(0.0, "Clearing package locks...")
            try { chrootManager.execChroot("rm -f /var/lib/apt/lists/lock /var/cache/apt/archives/lock /var/lib/dpkg/lock*; dpkg --configure -a 2>/dev/null || true") } catch (_: Exception) {}

            onProgress(0.05, "Updating package lists...")
            chrootManager.execChroot("apt-get update -y")

            // dbus is CRITICAL - without it no session can start
            onProgress(0.10, "Installing D-Bus (required)...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends dbus dbus-x11 policykit-1 packagekit")

            onProgress(0.20, "Installing Phosh & Compositor...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends phoc phosh")

            onProgress(0.40, "Installing GUI Dependencies...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends squeekboard phosh-mobile-settings gnome-settings-daemon gnome-settings-daemon-common librsvg2-common gnome-console adwaita-icon-theme fonts-cantarell")

            onProgress(0.55, "Installing compilation tools...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends gcc libxcb1-dev libxcb-dri3-dev libc6-dev libvulkan1 vulkan-tools wget curl")

            onProgress(0.70, "Building universal hooks (Socket)...")
            chrootManager.execChroot("""
                bash -c "cat > /tmp/socket_hook.c << 'EOF'
#define _GNU_SOURCE
#include <sys/socket.h>
#include <sys/un.h>
#include <dlfcn.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <stddef.h>
int connect(int sockfd, const struct sockaddr *addr, socklen_t addrlen) {
    int (*real_connect)(int, const struct sockaddr *, socklen_t) = dlsym(RTLD_NEXT, \"connect\");
    if (addr && addr->sa_family == AF_UNIX) {
        struct sockaddr_un *un = (struct sockaddr_un *)addr;
        if (strstr(un->sun_path, \".X11-unix/X\")) {
            struct sockaddr_un abstract_addr;
            memset(&abstract_addr, 0, sizeof(abstract_addr));
            abstract_addr.sun_family = AF_UNIX;
            const char *tmpdir = getenv(\"TMPDIR\");
            if (!tmpdir) tmpdir = \"/tmp\";
            snprintf(abstract_addr.sun_path + 1, sizeof(abstract_addr.sun_path) - 1, \"%s/.X11-unix/X0\", tmpdir);
            socklen_t abs_len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(abstract_addr.sun_path + 1);
            return real_connect(sockfd, (struct sockaddr *)&abstract_addr, abs_len);
        }
    }
    return real_connect(sockfd, addr, addrlen);
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libsocket_hook.so /tmp/socket_hook.c -ldl"
            """.trimIndent())

            onProgress(0.85, "Building universal hooks (DRI3)...")
            chrootManager.execChroot("""
                bash -c "cat > /tmp/nodri3.c << 'EOF'
#include <xcb/xcb.h>
#include <dlfcn.h>
#include <stdio.h>
extern xcb_extension_t xcb_dri3_id;
const xcb_query_extension_reply_t *xcb_get_extension_data(xcb_connection_t *c, xcb_extension_t *ext) {
    const xcb_query_extension_reply_t *(*real_fn)(xcb_connection_t*, xcb_extension_t*) = dlsym(RTLD_NEXT, \"xcb_get_extension_data\");
    if (ext == &xcb_dri3_id) {
        const xcb_query_extension_reply_t *reply = real_fn(c, ext);
        if (reply && reply->present) {
            ((xcb_query_extension_reply_t *)reply)->present = 0;
        }
        return reply;
    }
    return real_fn(c, ext);
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libnodri3.so /tmp/nodri3.c -lxcb -ldl -lxcb-dri3"
            """.trimIndent())

            chrootManager.execChroot("""
                bash -c "cat > /tmp/close_range_compat.c << 'EOF'
#define _GNU_SOURCE
#include <errno.h>
int close_range(unsigned int first, unsigned int last, int flags) {
    (void) first; (void) last; (void) flags;
    errno = ENOSYS;
    return -1;
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libmtos-close-range.so /tmp/close_range_compat.c"
            """.trimIndent())

            onProgress(0.90, "Installing Built-in Apps & Store...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends software-properties-common nautilus gnome-calculator gnome-clocks megapixels gnome-software gnome-software-plugin-flatpak flatpak")

            onProgress(0.95, "Setting up Firefox...")
            chrootManager.execChroot("""
                bash -c "add-apt-repository -y ppa:mozillateam/ppa && echo 'Package: *\nPin: release o=LP-PPA-mozillateam\nPin-Priority: 1001\n\nPackage: firefox\nPin: version 1:1snap1-0ubuntu2\nPin-Priority: -1' > /etc/apt/preferences.d/mozilla-firefox && apt-get update && DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends firefox"
            """.trimIndent())

            onProgress(0.97, "Configuring Flathub...")
            ensureFlathub(chrootManager)

            onProgress(0.98, "Building icon caches...")
            chrootManager.execChroot("""
                bash -c "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin && GDK_LOADER=${'$'}(find /usr/lib -name gdk-pixbuf-query-loaders | head -n 1) && ${'$'}GDK_LOADER > ${'$'}(dirname ${'$'}GDK_LOADER)/2.10.0/loaders.cache && gtk-update-icon-cache -f -t /usr/share/icons/hicolor && gtk-update-icon-cache -f -t /usr/share/icons/Adwaita && glib-compile-schemas /usr/share/glib-2.0/schemas"
            """.trimIndent())

            onProgress(1.0, "Phosh installed!")
        } catch (e: Exception) {
            Log.e(TAG, "Phosh install failed: ${e.message}", e)
            onProgress(-1.0, "Phosh install failed: ${e.message}")
        }
    }

    private fun calculateDirSize(dir: File): Long =
        if (!dir.exists()) 0 else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
}
