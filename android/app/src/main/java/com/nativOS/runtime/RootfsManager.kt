package com.nativOS.runtime

import android.content.Context
import android.util.Log
import com.nativOS.settings.NativOSPreferences
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Manages Linux rootfs downloads, extraction, and lifecycle.
 * Ported from DroidDesk's RootfsManager, adapted for NativOS.
 */
class RootfsManager(private val context: Context) {

    companion object {
        private const val TAG = "NativOS.RootfsManager"
        private const val BUFFER_SIZE = 8192
        private const val BUNDLED_ROOTFS_ASSET = "rootfs/nativos-rootfs-arm64.tgz"

        val DISTRO_URLS = mapOf(
            "alpine" to "https://dl-cdn.alpinelinux.org/alpine/v3.21/releases/aarch64/alpine-minirootfs-3.21.3-aarch64.tar.gz",
            "debian" to "https://cloud.debian.org/images/cloud/trixie/daily/latest/debian-13-nocloud-arm64-daily.tar.xz",
            "ubuntu" to "https://cdimage.ubuntu.com/ubuntu-base/releases/24.04/release/ubuntu-base-24.04.4-base-arm64.tar.gz"
        )
        val DISTRO_NAMES = mapOf(
            "alpine" to "Alpine Linux 3.21 (postmarketOS)",
            "debian" to "Debian 13 (Trixie)",
            "ubuntu" to "Ubuntu 24.04 Base"
        )
        const val DEFAULT_DISTRO = "alpine"

        data class DesktopEnvironmentInfo(
            val id: String,
            val name: String,
            val description: String,
            val category: String,
            val binaryCheck: String
        )

        val SUPPORTED_DESKTOPS = listOf(
            DesktopEnvironmentInfo("phosh", "Phosh (Mobile GNOME)", "Адаптивный мобильный интерфейс postmarketOS на базе Wayland и Phoc", "Mobile UI", "phosh"),
            DesktopEnvironmentInfo("plasma-mobile", "Plasma Mobile", "Мобильная среда KDE на базе KWin Wayland с поддержкой виджетов", "Mobile UI", "plasma-mobile"),
            DesktopEnvironmentInfo("sxmo", "Sxmo (Sway)", "Легковесное тайловое окружение с управлением жестами и горячими клавишами (120 FPS)", "Mobile UI", "sway"),
            DesktopEnvironmentInfo("xfce4", "XFCE 4", "Быстрый, стабильный и нетребовательный к ресурсам классический рабочий стол", "Desktop UI", "xfce4-session"),
            DesktopEnvironmentInfo("gnome", "GNOME Shell", "Полноценная современная среда GNOME с доком и плавными анимациями", "Desktop UI", "gnome-shell"),
            DesktopEnvironmentInfo("plasma", "KDE Plasma", "Мощный настраиваемый рабочий стол с широкими возможностями кастомизации", "Desktop UI", "plasma-desktop"),
            DesktopEnvironmentInfo("lxqt", "LXQt", "Ультрабыстрое легковесное модульное окружение на базе Qt", "Desktop UI", "lxqt-session"),
            DesktopEnvironmentInfo("mate", "MATE", "Традиционный рабочий стол с классической панелью задач и меню", "Desktop UI", "mate-session")
        )
    }

    private val baseDir: File get() = context.filesDir
    private val rootfsDir: File get() = File(baseDir, "rootfs")
    private val downloadDir: File get() = File(baseDir, "downloads")
    private val configFile: File get() = File(baseDir, "distro.conf")
    private val setupCompleteFile: File get() = File(baseDir, "SETUP_COMPLETE")
    private val rootShell = RootShell(context)
    val loopDeviceManager = LoopDeviceManager(context)
    val rootfsImageFile: File get() = File(baseDir, LoopDeviceManager.DEFAULT_IMAGE_NAME)

    fun isImageMode(): Boolean = NativOSPreferences.storageMode(context) == "image"

    fun ensureImageMounted(): Boolean {
        if (!isImageMode()) return true
        if (loopDeviceManager.isMounted(rootfsDir)) return true
        if (!rootfsImageFile.exists()) return false
        val fsType = NativOSPreferences.storageFsType(context)
        return loopDeviceManager.mountImage(rootfsImageFile, rootfsDir, fsType)
    }

    fun getInstalledDistro(): String =
        if (configFile.exists()) configFile.readText().trim()
        else NativOSPreferences.preferredDistro(context)

    fun getRootfsPath(): String = rootfsDir.absolutePath

    fun isRootfsReady(): Boolean {
        if (isImageMode()) {
            ensureImageMounted()
        }
        return rootfsDir.exists() &&
            (File(rootfsDir, "bin").exists() || File(rootfsDir, "usr/bin").exists() || File(rootfsDir, "bin/sh").exists()) &&
            File(rootfsDir, "usr").exists() && File(rootfsDir, "etc").exists()
    }

    /**
     * Unmount all mounts located under rootfsDir before any wipe or extraction.
     * Prevents deleteRecursively from traversing bind-mounts (e.g. host filesDir)
     * and deleting downloaded tarballs or host files.
     */
    fun unmountAllMountsUnderRootfs() {
        if (!rootShell.hasRoot()) return
        try {
            loopDeviceManager.unmountExternalDrives(rootfsDir)
            val rootPath = rootfsDir.absolutePath
            val mounts = rootShell.exec("cat /proc/mounts").lines()
            val targets = mounts.mapNotNull { line ->
                val parts = line.split("\\s+".toRegex())
                if (parts.size >= 2) parts[1] else null
            }.filter { it.startsWith(rootPath) || it.contains("com.nativOS/files/rootfs") }
             .distinct()
             .sortedByDescending { it.length }

            for (target in targets) {
                rootShell.exec("umount -l $target 2>/dev/null || true")
                Log.i(TAG, "Unmounted: $target")
            }
            if (isImageMode()) {
                loopDeviceManager.unmountImage(rootfsDir)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unmount mounts before extraction: ${e.message}")
        }
    }

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
            val target = File(downloadDir, "$DEFAULT_DISTRO-rootfs.tar.gz")
            val temporary = File(downloadDir, "$DEFAULT_DISTRO-rootfs.tar.gz.part")
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
            configFile.writeText(DEFAULT_DISTRO)
            onProgress(1.0, "Bundled Linux system ready")
            Log.i(TAG, "Staged bundled rootfs (${copied / (1024 * 1024)} MiB)")
            true
        } catch (error: Throwable) {
            File(downloadDir, "$DEFAULT_DISTRO-rootfs.tar.gz.part").delete()
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
    fun isApkBased(): Boolean =
        File(rootfsDir, "sbin/apk").exists() || File(rootfsDir, "usr/bin/apk").exists()

    fun isDebianBased(): Boolean =
        File(rootfsDir, "usr/bin/apt-get").exists() || File(rootfsDir, "usr/bin/dpkg").exists()

    /**
     * Repair a partially provisioned Phosh installation. The phoc binary can be
     * unpacked before the remaining packages finish, so it is not a sufficient
     * readiness marker by itself. Without the touchscreen schema Phosh aborts
     * immediately and the X11 cursor visibly flickers in a restart loop.
     */
    fun ensurePhoshRuntime(chrootManager: ChrootManager): Boolean {
        val schema = File(
            rootfsDir,
            "usr/share/glib-2.0/schemas/org.gnome.settings-daemon.peripherals.gschema.xml"
        )
        val desktopSchema = File(
            rootfsDir,
            "usr/share/glib-2.0/schemas/org.gnome.desktop.interface.gschema.xml"
        )
        val compiledSchemas = File(
            rootfsDir,
            "usr/share/glib-2.0/schemas/gschemas.compiled"
        )
        val svgLoaderDebian = File(
            rootfsDir,
            "usr/lib/aarch64-linux-gnu/gdk-pixbuf-2.0/2.10.0/loaders/libpixbufloader-svg.so"
        )
        val svgLoaderAlpine = File(
            rootfsDir,
            "usr/lib/gdk-pixbuf-2.0/2.10.0/loaders/libpixbufloader-svg.so"
        )
        val librsvgAlpine = File(
            rootfsDir,
            "usr/lib/librsvg-2.so"
        )

        // If schemas and svg loaders are ready, proceed without network repair
        if ((schema.exists() || desktopSchema.exists() || compiledSchemas.exists()) &&
            (svgLoaderDebian.exists() || svgLoaderAlpine.exists() || librsvgAlpine.exists())) {
            return true
        }

        Log.w(TAG, "Phosh runtime components missing; repairing interrupted provisioning")
        val result = if (isApkBased()) {
            chrootManager.execChroot(
                """
                    apk update || true
                    apk add --no-cache gnome-settings-daemon gsettings-desktop-schemas librsvg adwaita-icon-theme glib 2>/dev/null || true
                    if command -v glib-compile-schemas >/dev/null 2>&1; then
                        glib-compile-schemas /usr/share/glib-2.0/schemas 2>/dev/null || true
                    fi
                    gtk-update-icon-cache -f -t /usr/share/icons/hicolor 2>/dev/null || true
                    gtk-update-icon-cache -f -t /usr/share/icons/Adwaita 2>/dev/null || true
                    exit 0
                """.trimIndent()
            )
        } else {
            chrootManager.execChroot(
                """
                    dpkg --configure -a || true
                    apt-get update || exit 1
                    TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC \
                        apt-get install -y --no-install-recommends \
                        gnome-settings-daemon-common librsvg2-common || exit 1
                    glib-compile-schemas /usr/share/glib-2.0/schemas || exit 1
                    GDK_LOADER=${'$'}(find /usr/lib -name gdk-pixbuf-query-loaders | head -n 1)
                    if [ -x "${'$'}GDK_LOADER" ]; then
                        "${'$'}GDK_LOADER" > "${'$'}(dirname "${'$'}GDK_LOADER")/2.10.0/loaders.cache" || exit 1
                    fi
                    gtk-update-icon-cache -f -t /usr/share/icons/hicolor 2>/dev/null || true
                    gtk-update-icon-cache -f -t /usr/share/icons/Adwaita 2>/dev/null || true
                """.trimIndent()
            )
        }
        if (result == 0) {
            Log.i(TAG, "Phosh runtime schemas and SVG loader repaired")
            return true
        }
        Log.e(TAG, "Could not repair Phosh runtime components (exit $result)")
        return false
    }

    /** Install the adaptive Wayland terminal and remove the two legacy XTerm launchers. */
    fun ensureProfessionalTerminal(chrootManager: ChrootManager): Boolean {
        val console = File(rootfsDir, "usr/bin/kgx")
        val legacyXterm = File(rootfsDir, "usr/bin/xterm")
        val legacyGnomeTerminal = File(rootfsDir, "usr/bin/gnome-terminal")
        if (console.exists() && !legacyXterm.exists() && !legacyGnomeTerminal.exists()) return true

        val result = if (isApkBased()) {
            chrootManager.execChroot(
                """
                    if ! command -v kgx >/dev/null 2>&1; then
                        apk add --no-cache gnome-console || exit 1
                    fi
                """.trimIndent()
            )
        } else {
            chrootManager.execChroot(
                """
                    dpkg --configure -a || true
                    if ! command -v kgx >/dev/null 2>&1; then
                        apt-get update || exit 1
                        TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC \
                            apt-get install -y --no-install-recommends gnome-console || exit 1
                    fi
                    rm -f /usr/share/applications/debian-xterm.desktop \
                        /usr/share/applications/debian-uxterm.desktop \
                        /usr/share/applications/org.gnome.Terminal.desktop
                    TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive \
                        apt-get purge -y xterm gnome-terminal gnome-terminal-data || exit 1
                    update-desktop-database /usr/share/applications 2>/dev/null || true
                """.trimIndent()
            )
        }
        if (result == 0) {
            Log.i(TAG, "GNOME Console ready; legacy terminal launchers removed")
        } else {
            Log.w(TAG, "Could not provision GNOME Console (exit $result)")
        }
        return result == 0
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
        distro: String = getInstalledDistro().ifEmpty { DEFAULT_DISTRO },
        onProgress: (progress: Double, status: String) -> Unit
    ) {
        try {
            val url = DISTRO_URLS[distro] ?: throw IllegalArgumentException("Unknown distro: $distro")
            val distroName = DISTRO_NAMES[distro] ?: distro
            downloadDir.mkdirs()
            val ext = if (url.endsWith(".tar.xz")) "tar.xz" else "tar.gz"
            val targetFile = File(downloadDir, "$distro-rootfs.$ext")

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
            var tarball = File(downloadDir, "$distro-rootfs.tar.gz").takeIf { it.exists() && it.length() > 0 }
                ?: File(downloadDir, "$distro-rootfs.tar.xz").takeIf { it.exists() && it.length() > 0 }
                ?: downloadDir.listFiles()?.firstOrNull { it.name.startsWith(distro) && (it.name.endsWith(".tar.gz") || it.name.endsWith(".tar.xz")) && it.length() > 0 }

            if (tarball == null || !tarball.exists() || tarball.length() == 0L) {
                Log.w(TAG, "Rootfs tarball not found or empty, attempting download...")
                onProgress(0.0, "Downloading $distro filesystem...")
                downloadRootfs(distro, onProgress)
                tarball = File(downloadDir, "$distro-rootfs.tar.gz").takeIf { it.exists() && it.length() > 0 }
                    ?: File(downloadDir, "$distro-rootfs.tar.xz").takeIf { it.exists() && it.length() > 0 }
                    ?: downloadDir.listFiles()?.firstOrNull { it.name.startsWith(distro) && (it.name.endsWith(".tar.gz") || it.name.endsWith(".tar.xz")) && it.length() > 0 }
            }

            if (tarball == null || !tarball.exists() || tarball.length() == 0L) {
                onProgress(-1.0, "Rootfs tarball not found after download.")
                return
            }

            // CRITICAL: Unmount all active mounts under rootfsDir before wiping or extracting.
            // Otherwise deleteRecursively traverses bind mounts (like filesDir -> rootfs/data/user/0/...)
            // and deletes the downloaded tarball or host files!
            unmountAllMountsUnderRootfs()

            if (isImageMode() && rootShell.hasRoot()) {
                if (!rootfsImageFile.exists()) {
                    onProgress(0.05, "Creating sparse rootfs disk image...")
                    val fsType = NativOSPreferences.storageFsType(context)
                    loopDeviceManager.createSparseImage(rootfsImageFile, LoopDeviceManager.DEFAULT_IMAGE_SIZE_GB, fsType)
                }
                rootfsDir.mkdirs()
                loopDeviceManager.mountImage(rootfsImageFile, rootfsDir, NativOSPreferences.storageFsType(context))
            } else {
                if (rootfsDir.exists()) {
                    if (rootShell.hasRoot()) {
                        rootShell.exec("rm -rf ${rootfsDir.absolutePath}")
                    } else {
                        rootfsDir.deleteRecursively()
                    }
                }
                rootfsDir.mkdirs()
            }

            onProgress(0.1, "Extracting Linux filesystem...")
            Log.i(TAG, "Extracting rootfs from ${tarball.absolutePath}")

            var exitCode = -1
            var lastLine = ""
            if (rootShell.hasRoot()) {
                var lineCount = 0
                exitCode = rootShell.exec("tar -xf ${tarball.absolutePath} -C ${rootfsDir.absolutePath}") { chunk ->
                    lastLine = chunk.trim()
                    lineCount++
                    if (lineCount % 10 == 0) onProgress(0.1 + (lineCount % 500) / 1000.0, "Extracting files...")
                }
            } else {
                val process = ProcessBuilder("tar", "-xf", tarball.absolutePath, "-C", rootfsDir.absolutePath)
                    .redirectErrorStream(true).start()
                val reader = process.inputStream.bufferedReader()
                var line: String?
                var lineCount = 0
                while (reader.readLine().also { line = it } != null) {
                    lastLine = line!!
                    lineCount++
                    if (lineCount % 500 == 0) onProgress(0.1 + (lineCount % 5000) / 10000.0, "Extracting files...")
                }
                exitCode = process.waitFor()
            }

            val binDir = File(rootfsDir, "bin")
            val usrBinDir = File(rootfsDir, "usr/bin")
            if (exitCode != 0 && (!binDir.exists() || binDir.list()?.isEmpty() == true) && (!usrBinDir.exists() || usrBinDir.list()?.isEmpty() == true))
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

    fun configureRootfs() {
        File(rootfsDir, "etc/resolv.conf").apply {
            parentFile?.mkdirs()
            writeText("nameserver 8.8.8.8\nnameserver 8.8.4.4\nnameserver 1.1.1.1\n")
        }
        File(rootfsDir, "etc/hostname").apply {
            parentFile?.mkdirs()
            writeText("nativOS\n")
        }
        File(rootfsDir, "etc/hosts").apply {
            parentFile?.mkdirs()
            writeText("127.0.0.1 localhost\n127.0.0.1 nativOS\n::1 localhost\n")
        }
        File(rootfsDir, "etc/profile.d/nativOS.sh").apply {
            parentFile?.mkdirs()
            writeText("#!/bin/sh\nexport DISPLAY=:0\nexport XDG_RUNTIME_DIR=/tmp/runtime-root\nmkdir -p /tmp/runtime-root 2>/dev/null\n")
        }
        File(rootfsDir, "etc/sudoers.d/nativOS").apply {
            parentFile?.mkdirs()
            writeText("Defaults !requiretty\nroot ALL=(ALL) NOPASSWD: ALL\n")
        }

        if (isApkBased() || getInstalledDistro() == "alpine") {
            File(rootfsDir, "etc/apk").mkdirs()
            File(rootfsDir, "etc/apk/repositories").writeText(
                "https://dl-cdn.alpinelinux.org/alpine/v3.21/main\n" +
                "https://dl-cdn.alpinelinux.org/alpine/v3.21/community\n" +
                "https://mirror.postmarketos.org/postmarketos/v24.12\n"
            )
        } else {
            File(rootfsDir, "etc/apt/apt.conf.d").mkdirs()
            File(rootfsDir, "etc/apt/apt.conf.d/99-disable-sandbox").writeText("APT::Sandbox::User \"root\";\n")
            File(rootfsDir, "etc/apt/sources.list").apply {
                parentFile?.mkdirs()
                writeText(
                    "deb http://deb.debian.org/debian trixie main contrib non-free non-free-firmware\n" +
                    "deb http://deb.debian.org/debian-security trixie-security main contrib non-free non-free-firmware\n" +
                    "deb http://deb.debian.org/debian trixie-updates main contrib non-free non-free-firmware\n"
                )
            }
        }

        listOf(
            "tmp", "tmp/runtime-root", "run", "var/run", "dev/shm",
            "run/nativOS", "var/log/nativOS", "etc/nativOS",
            "usr/local/sbin", "usr/local/bin", "usr/local/lib"
        ).forEach { File(rootfsDir, it).mkdirs() }

        // Deploy the modular nativOS-init system script
        NativOSInit.installTo(rootfsDir)
    }

    fun isDesktopInstalled(envId: String): Boolean {
        val de = SUPPORTED_DESKTOPS.find { it.id == envId } ?: return false
        return File(rootfsDir, "usr/bin/${de.binaryCheck}").exists() ||
               File(rootfsDir, "usr/local/bin/${de.binaryCheck}").exists() ||
               File(rootfsDir, "bin/${de.binaryCheck}").exists()
    }

    fun installPhosh(chrootManager: ChrootManager, onProgress: (Double, String) -> Unit) {
        installDesktopEnvironment(chrootManager, "phosh", onProgress)
    }

    fun installDesktopEnvironment(
        chrootManager: ChrootManager,
        env: String = NativOSPreferences.desktopEnvironment(context),
        onProgress: (Double, String) -> Unit
    ): Boolean {
        return if (isApkBased() || getInstalledDistro() == "alpine") {
            installPostmarketOSDesktop(chrootManager, env, onProgress)
        } else {
            installDebianDesktop(chrootManager, env, onProgress)
        }
    }

    fun switchDesktopEnvironment(
        chrootManager: ChrootManager,
        targetEnv: String,
        onProgress: (Double, String) -> Unit
    ): Boolean {
        if (isDesktopInstalled(targetEnv)) {
            NativOSPreferences.setDesktopEnvironment(context, targetEnv)
            onProgress(1.0, "Окружение переключено на $targetEnv")
            return true
        }
        return installDesktopEnvironment(chrootManager, targetEnv, onProgress)
    }

    private fun installPostmarketOSDesktop(
        chrootManager: ChrootManager,
        env: String,
        onProgress: (Double, String) -> Unit
    ): Boolean {
        return try {
            val envInfo = SUPPORTED_DESKTOPS.find { it.id == env } ?: SUPPORTED_DESKTOPS[0]
            onProgress(0.05, "Обновление репозиториев Alpine & postmarketOS...")
            chrootManager.execChroot("apk update --allow-untrusted || apk update")
            chrootManager.execChroot("apk add --no-cache --allow-untrusted postmarketos-keys 2>/dev/null || true")
            chrootManager.execChroot("apk update")

            onProgress(0.20, "Установка системных служб D-Bus и OpenRC...")
            chrootManager.execChroot("apk add --no-cache openrc dbus dbus-x11 polkit bash shadow sudo curl wget ca-certificates python3 py3-pip gcompat")

            onProgress(0.40, "Установка графической среды ${envInfo.name}...")
            val pkgCmd = when (env) {
                "plasma-mobile" ->
                    "apk add --no-cache postmarketos-ui-plasma-mobile || apk add --no-cache plasma-mobile plasma-phone-components kwin breeze breeze-icons plasma-settings"
                "sxmo" ->
                    "apk add --no-cache postmarketos-ui-sxmo-de-sway || apk add --no-cache sxmo-utils sway foot wofi mako bemenu"
                "xfce4" ->
                    "apk add --no-cache postmarketos-ui-xfce4 || apk add --no-cache xfce4 xfce4-terminal xfce4-session mousepad adwaita-icon-theme"
                "gnome" ->
                    "apk add --no-cache postmarketos-ui-gnome || apk add --no-cache gnome-shell gnome-session nautilus gnome-console mutter adwaita-icon-theme"
                "plasma" ->
                    "apk add --no-cache postmarketos-ui-plasma-desktop || apk add --no-cache plasma-desktop kwin dolphin konsole breeze breeze-icons"
                "lxqt" ->
                    "apk add --no-cache postmarketos-ui-lxqt || apk add --no-cache lxqt-core lxqt-session pcmanfm-qt qterminal adwaita-icon-theme"
                "mate" ->
                    "apk add --no-cache postmarketos-ui-mate || apk add --no-cache mate-desktop mate-session-manager caja marco mate-terminal adwaita-icon-theme"
                else ->
                    "apk add --no-cache postmarketos-ui-phosh || apk add --no-cache phoc phosh squeekboard feedbackd adwaita-icon-theme fonts-cantarell desktop-file-utils gtk-update-icon-cache glib gsettings-desktop-schemas"
            }
            chrootManager.execChroot(pkgCmd)

            onProgress(0.65, "Установка базовых приложений и ускорения GPU...")
            val appCmd = when (env) {
                "plasma-mobile", "plasma" -> "apk add --no-cache konsole dolphin mesa-vulkan-freedreno 2>/dev/null || true"
                "sxmo" -> "apk add --no-cache foot wofi mesa-vulkan-freedreno 2>/dev/null || true"
                "xfce4" -> "apk add --no-cache xfce4-terminal mousepad mesa-vulkan-freedreno 2>/dev/null || true"
                "lxqt" -> "apk add --no-cache qterminal pcmanfm-qt mesa-vulkan-freedreno 2>/dev/null || true"
                "mate" -> "apk add --no-cache mate-terminal caja mesa-vulkan-freedreno 2>/dev/null || true"
                else -> "apk add --no-cache gnome-console gnome-calculator gnome-clocks megapixels mesa-vulkan-freedreno 2>/dev/null || true"
            }
            chrootManager.execChroot(appCmd)

            onProgress(0.80, "Сборка библиотек совместимости...")
            chrootManager.execChroot("apk add --no-cache build-base libxcb-dev 2>/dev/null || true")
            buildUniversalHooks(chrootManager)

            onProgress(0.95, "Настройка nativOS-init и схем...")
            NativOSInit.installTo(rootfsDir)
            chrootManager.execChroot("glib-compile-schemas /usr/share/glib-2.0/schemas 2>/dev/null || true")
            chrootManager.execChroot("gtk-update-icon-cache -f -t /usr/share/icons/hicolor 2>/dev/null || true")
            chrootManager.execChroot("gtk-update-icon-cache -f -t /usr/share/icons/Adwaita 2>/dev/null || true")

            NativOSPreferences.setDesktopEnvironment(context, env)
            onProgress(1.0, "${envInfo.name} успешно установлена!")
            true
        } catch (e: Exception) {
            Log.e(TAG, "postmarketOS install failed: ${e.message}", e)
            onProgress(-1.0, "postmarketOS install failed: ${e.message}")
            false
        }
    }

    private fun installDebianDesktop(
        chrootManager: ChrootManager,
        env: String,
        onProgress: (Double, String) -> Unit
    ): Boolean {
        return try {
            val envInfo = SUPPORTED_DESKTOPS.find { it.id == env } ?: SUPPORTED_DESKTOPS[0]
            onProgress(0.0, "Очистка блокировок пакетов...")
            try { chrootManager.execChroot("rm -f /var/lib/apt/lists/lock /var/cache/apt/archives/lock /var/lib/dpkg/lock*; dpkg --configure -a 2>/dev/null || true") } catch (_: Exception) {}

            onProgress(0.05, "Обновление репозиториев Debian 13...")
            chrootManager.execChroot("apt-get update -y")

            onProgress(0.15, "Установка D-Bus и системных утилит...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends dbus dbus-x11 policykit-1 packagekit sudo ca-certificates python3 python3-pip")

            onProgress(0.40, "Установка графической среды ${envInfo.name}...")
            val pkgCmd = when (env) {
                "plasma-mobile" ->
                    "TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends plasma-mobile plasma-phone-components kwin-wayland breeze-icon-theme konsole dolphin"
                "sxmo" ->
                    "TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends sway foot wofi mako alacritty pulseaudio-utils"
                "xfce4" ->
                    "TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends xfce4 xfce4-terminal xfce4-session mousepad"
                "gnome" ->
                    "TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends gnome-shell gnome-session nautilus gnome-console mutter"
                "plasma" ->
                    "TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends plasma-desktop kwin-x11 dolphin konsole breeze-icon-theme"
                "lxqt" ->
                    "TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends lxqt-core lxqt-session pcmanfm-qt qterminal"
                "mate" ->
                    "TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends mate-desktop mate-session-manager caja marco mate-terminal"
                else ->
                    "TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends phoc phosh squeekboard phosh-mobile-settings gnome-settings-daemon gnome-settings-daemon-common librsvg2-common adwaita-icon-theme fonts-cantarell gnome-console nautilus gnome-calculator gnome-clocks megapixels firefox-esr"
            }
            chrootManager.execChroot(pkgCmd)

            onProgress(0.80, "Сборка библиотек совместимости...")
            chrootManager.execChroot("TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive TZ=Etc/UTC apt-get install -y --no-install-recommends gcc libxcb1-dev libxcb-dri3-dev libc6-dev libvulkan1 vulkan-tools wget curl 2>/dev/null || true")
            buildUniversalHooks(chrootManager)

            onProgress(0.95, "Настройка nativOS-init и схем...")
            NativOSInit.installTo(rootfsDir)
            chrootManager.execChroot("glib-compile-schemas /usr/share/glib-2.0/schemas 2>/dev/null || true")
            chrootManager.execChroot("gtk-update-icon-cache -f -t /usr/share/icons/hicolor 2>/dev/null || true")
            chrootManager.execChroot("gtk-update-icon-cache -f -t /usr/share/icons/Adwaita 2>/dev/null || true")

            NativOSPreferences.setDesktopEnvironment(context, env)
            onProgress(1.0, "Debian 13 ${envInfo.name} установлена!")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Debian install failed: ${e.message}", e)
            onProgress(-1.0, "Debian install failed: ${e.message}")
            false
        }
    }

    private fun buildUniversalHooks(chrootManager: ChrootManager) {
        chrootManager.execChroot("""
            sh -c "cat > /tmp/socket_hook.c << 'EOF'
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
gcc -shared -fPIC -o /usr/local/lib/libsocket_hook.so /tmp/socket_hook.c 2>/dev/null || gcc -shared -fPIC -o /usr/local/lib/libsocket_hook.so /tmp/socket_hook.c -ldl"
        """.trimIndent())

        chrootManager.execChroot("""
            sh -c "cat > /tmp/nodri3.c << 'EOF'
#define _GNU_SOURCE
#include <stdio.h>
#include <dlfcn.h>
#include <string.h>
#include <xcb/xcb.h>

struct my_xcb_extension_t {
    const char *name;
    int global_id;
};

const struct xcb_query_extension_reply_t *
xcb_get_extension_data(xcb_connection_t *c, xcb_extension_t *ext) {
    static const struct xcb_query_extension_reply_t * (*real_fn)(xcb_connection_t *, xcb_extension_t *) = NULL;
    if (!real_fn) {
        real_fn = dlsym(RTLD_NEXT, \"xcb_get_extension_data\");
    }
    struct my_xcb_extension_t *my_ext = (struct my_xcb_extension_t *)ext;
    if (my_ext && my_ext->name && strcmp(my_ext->name, \"DRI3\") == 0) {
        return NULL;
    }
    return real_fn ? real_fn(c, ext) : NULL;
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libnodri3.so /tmp/nodri3.c -ldl 2>/dev/null || gcc -shared -fPIC -o /usr/local/lib/libnodri3.so /tmp/nodri3.c -ldl -lxcb 2>/dev/null || true"
        """.trimIndent())

        chrootManager.execChroot("""
            sh -c "cat > /tmp/close_range_compat.c << 'EOF'
#define _GNU_SOURCE
#include <errno.h>
int close_range(unsigned int first, unsigned int last, int flags) {
    (void) first; (void) last; (void) flags;
    errno = ENOSYS;
    return -1;
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libnativos-close-range.so /tmp/close_range_compat.c"
        """.trimIndent())
    }

    private fun calculateDirSize(dir: File): Long =
        if (!dir.exists()) 0 else dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /**
     * Create a compressed tarball snapshot of the rootfs, excluding virtual mounts.
     */
    fun createBackup(
        targetArchive: File,
        onProgress: (progress: Double, status: String) -> Unit
    ): Boolean {
        try {
            if (!isRootfsReady()) {
                onProgress(-1.0, "Rootfs is not initialized")
                return false
            }
            targetArchive.parentFile?.mkdirs()
            onProgress(0.1, "Creating system snapshot archive...")
            Log.i(TAG, "Creating backup: ${targetArchive.absolutePath}")

            val excludes = listOf(
                "--exclude=./proc/*",
                "--exclude=./sys/*",
                "--exclude=./dev/*",
                "--exclude=./tmp/*",
                "--exclude=./run/*",
                "--exclude=./var/tmp/*",
                "--exclude=./var/cache/*"
            )
            val command = mutableListOf("tar", "-czf", targetArchive.absolutePath)
            command.addAll(excludes)
            command.addAll(listOf("-C", rootfsDir.absolutePath, "."))

            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .start()
            val reader = process.inputStream.bufferedReader()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                Log.d(TAG, "TAR BACKUP: $line")
            }
            val exitCode = process.waitFor()
            if (exitCode != 0 && !targetArchive.exists()) {
                throw RuntimeException("Backup failed with code $exitCode")
            }
            onProgress(1.0, "Backup saved (${targetArchive.length() / (1024 * 1024)} MiB)")
            Log.i(TAG, "Backup created successfully: ${targetArchive.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create backup", e)
            onProgress(-1.0, "Backup failed: ${e.message}")
            return false
        }
    }

    /**
     * Restore rootfs from a previously created snapshot archive.
     */
    fun restoreBackup(
        archiveFile: File,
        chrootManager: ChrootManager,
        onProgress: (progress: Double, status: String) -> Unit
    ): Boolean {
        try {
            if (!archiveFile.exists() || archiveFile.length() == 0L) {
                onProgress(-1.0, "Backup file not found or empty")
                return false
            }
            onProgress(0.1, "Stopping active Linux session...")
            chrootManager.stopSession()

            onProgress(0.3, "Extracting snapshot files...")
            if (rootfsDir.exists()) {
                rootfsDir.deleteRecursively()
            }
            rootfsDir.mkdirs()

            val process = ProcessBuilder("tar", "-xzf", archiveFile.absolutePath, "-C", rootfsDir.absolutePath)
                .redirectErrorStream(true)
                .start()
            val reader = process.inputStream.bufferedReader()
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                Log.d(TAG, "TAR RESTORE: $line")
            }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                throw RuntimeException("Extraction failed with code $exitCode")
            }

            onProgress(0.8, "Configuring restored environment...")
            configureRootfs()
            NativOSInit.installTo(rootfsDir)
            setupCompleteFile.writeText("done")
            onProgress(1.0, "System snapshot restored successfully")
            Log.i(TAG, "Snapshot restored from ${archiveFile.absolutePath}")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed", e)
            onProgress(-1.0, "Restore failed: ${e.message}")
            return false
        }
    }

    /**
     * List available backup archives in common locations.
     */
    fun listBackups(): List<File> {
        val backupDirs = listOf(
            File("/sdcard/Download/nativOS-backups"),
            File("/sdcard/Download"),
            File(baseDir, "backups")
        )
        val results = mutableListOf<File>()
        for (dir in backupDirs) {
            if (dir.exists() && dir.isDirectory) {
                dir.listFiles()?.filter {
                    it.isFile && it.name.startsWith("nativos-backup") &&
                    (it.name.endsWith(".tar.gz") || it.name.endsWith(".tgz"))
                }?.let { results.addAll(it) }
            }
        }
        return results.sortedByDescending { it.lastModified() }
    }

    /**
     * Install specialized software profile (minimal, standard, developer, media).
     */
    fun installSoftwareProfile(
        chrootManager: ChrootManager,
        profile: String,
        onProgress: (progress: Double, status: String) -> Unit
    ): Boolean {
        if (profile == "minimal") return true
        val isApk = isApkBased()
        onProgress(0.1, "Installing software profile: $profile...")
        val cmd = when (profile) {
            "standard" -> if (isApk) {
                "apk add --no-cache firefox evince file-roller"
            } else {
                "TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends firefox-esr evince file-roller"
            }
            "developer" -> if (isApk) {
                "apk add --no-cache git python3 py3-pip build-base openssh-server neovim tmux curl"
            } else {
                "TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends git python3 python3-pip build-essential openssh-server neovim tmux curl"
            }
            "media" -> if (isApk) {
                "apk add --no-cache gimp vlc mpv"
            } else {
                "TMPDIR=/tmp DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends gimp vlc mpv"
            }
            else -> return true
        }
        val exitCode = chrootManager.execChroot(cmd)
        if (exitCode == 0) {
            onProgress(1.0, "Profile $profile installed successfully")
            return true
        } else {
            onProgress(0.9, "Profile $profile installed with non-critical warnings ($exitCode)")
            return false
        }
    }
}
