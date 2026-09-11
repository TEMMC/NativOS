package com.nativOS.runtime

import android.content.Context
import android.util.Log
import com.nativOS.settings.NativOSPreferences
import java.io.File
import java.security.MessageDigest

/**
 * Manages the chroot Ubuntu filesystem: mounting, session lifecycle,
 * and command execution inside the chroot.
 *
 * Adapted from DroidDesk's ChrootRuntime for NativOS —
 * focused on Phosh/Wayland instead of XFCE.
 */
class ChrootManager(private val context: Context) {

    companion object {
        private const val TAG = "NativOS.ChrootManager"
        private const val TURNIP_VERSION = "25.0.7"
        private const val TURNIP_ASSET = "gpu/mesa-vulkan-drivers-kgsl-25.0.7-arm64.deb"
        private const val TURNIP_SHA256 = "66b11a94835f66e80efc8556477334a14dc68456a76e31ada3cd2d440869c5d5"
        private val mountLock = Any()

        @Volatile private var sessionProcess: Process? = null
    }

    private val rootShell = RootShell(context)
    private val performanceManager = PerformanceManager(context)
    private val loopDeviceManager = LoopDeviceManager(context)

    private val baseDir: File get() = context.filesDir
    private val rootfsDir: File get() = File(baseDir, "rootfs")
    private val tmpDir: File get() = File(baseDir, "tmp")
    private val shmDir: File get() = File(baseDir, "shm")
    private val x11HostDir: File get() = File(tmpDir, ".X11-unix")
    private val bridgeSocketDir: File get() = File(baseDir, "bridge")
    // Keep the shared directory in app-owned storage. Direct access to
    // /data/media and /storage/emulated is device/ROM dependent under SELinux.
    // SharedFolderProvider exposes this directory to Android's Files UI.
    private val androidSharedDir: File
        get() = File(baseDir, "shared")
    private val turnipRoot: File get() = File(rootfsDir, "opt/nativos-gpu/turnip-$TURNIP_VERSION")
    private val turnipIcd: File get() = File(turnipRoot, "usr/share/vulkan/icd.d/freedreno_icd.aarch64.json")

    // ── Status ──

    fun hasRoot(): Boolean = rootShell.hasRoot()

    fun isRootfsReady(): Boolean {
        if (NativOSPreferences.storageMode(context) == "image") {
            val img = File(baseDir, LoopDeviceManager.DEFAULT_IMAGE_NAME)
            if (img.exists() && !loopDeviceManager.isMounted(rootfsDir)) {
                loopDeviceManager.mountImage(img, rootfsDir, NativOSPreferences.storageFsType(context))
            }
        }
        return File(rootfsDir, "bin/sh").exists() ||
            File(rootfsDir, "usr/bin/bash").exists() ||
            File(rootfsDir, "bin/bash").exists()
    }

    fun isPhoshInstalled(): Boolean =
        File(rootfsDir, "usr/bin/phosh-session").exists() ||
        File(rootfsDir, "usr/bin/phoc").exists() ||
        File(rootfsDir, "usr/libexec/phosh").exists()

    fun isRunning(): Boolean {
        if (sessionProcess?.isAlive == true) return true
        val deCheck = "pgrep -x phosh >/dev/null 2>&1 || pgrep -x phoc >/dev/null 2>&1 || " +
            "pgrep -x plasmashell >/dev/null 2>&1 || pgrep -x kwin_wayland >/dev/null 2>&1 || " +
            "pgrep -x sway >/dev/null 2>&1 || pgrep -x xfce4-session >/dev/null 2>&1 || " +
            "pgrep -x gnome-shell >/dev/null 2>&1 || pgrep -x lxqt-session >/dev/null 2>&1 || " +
            "pgrep -x mate-session >/dev/null 2>&1"
        val socketCheck = "test -S /tmp/runtime-root/wayland-0 || test -S /tmp/.X11-unix/X0"
        return execChroot("($socketCheck) || ($deCheck)") == 0
    }

    fun getRootfsPath(): String = rootfsDir.absolutePath

    fun getBridgeSocketPath(): String = File(bridgeSocketDir, "bridge.sock").absolutePath

    // ── Mount handling ──

    /**
     * Ensure /dev, /proc, /sys, /dev/pts and tmpfs mounts are active.
     * Also bind-mounts the bridge socket directory into the chroot.
     */
    fun ensureMounts() = synchronized(mountLock) {
        ensureMountsLocked()
    }

    private fun ensureMountsLocked() {
        if (!hasRoot()) return

        // If running in image mode, mount the rootfs.img loop device before bind-mounting
        if (NativOSPreferences.storageMode(context) == "image") {
            val img = File(baseDir, LoopDeviceManager.DEFAULT_IMAGE_NAME)
            if (img.exists() && !loopDeviceManager.isMounted(rootfsDir)) {
                loopDeviceManager.mountImage(img, rootfsDir, NativOSPreferences.storageFsType(context))
            }
        }

        // Services such as polkit drop root privileges and must still be able to
        // enter the chroot root. Android's private parent directory remains 0700.
        rootShell.exec("chmod 755 ${rootfsDir.absolutePath}")

        // Bubblewrap's privileged fallback needs the sandbox root to be a mount
        // point. Do this before the nested mounts so /dev, /proc, and /sys stay
        // visible inside that mount rather than being hidden by it.
        val existingMounts = rootShell.exec("mount").lines()
        if (existingMounts.none { it.contains(" on ${rootfsDir.absolutePath} ") }) {
            rootShell.exec("mount --bind ${rootfsDir.absolutePath} ${rootfsDir.absolutePath}")
            Log.i(TAG, "Made chroot root a bind mount")
        }

        val mounts = rootShell.exec("mount").lines()
        fun isMounted(path: String): Boolean {
            val absolute = File(rootfsDir, path).absolutePath
            return mounts.any { it.contains(" on $absolute ") }
        }

        // Core filesystem mounts
        mountIfNeeded("/dev", "--bind /dev") { isMounted("dev") }
        mountIfNeeded("/dev/pts", "-t devpts devpts") { isMounted("dev/pts") }
        // wlroots sends its X11 framebuffer to the embedded server through
        // MIT-SHM. An anonymous tmpfs gets the generic tmpfs SELinux label,
        // which Android denies to our isolated :x11 app process. Keep /dev/shm
        // on app-owned storage so both the rooted compositor and X11 can use it.
        shmDir.mkdirs()
        val chrootShmDir = File(rootfsDir, "dev/shm").absolutePath
        val shmMount = mounts.firstOrNull { it.contains(" on $chrootShmDir ") }
        if (shmMount == null || !shmMount.startsWith("${shmDir.absolutePath} ")) {
            if (shmMount != null) rootShell.exec("umount $chrootShmDir")
            rootShell.exec("mkdir -p $chrootShmDir && mount --bind ${shmDir.absolutePath} $chrootShmDir")
            Log.i(TAG, "Bound app-owned shared memory directory into chroot")
        }
        mountIfNeeded("/proc", "--bind /proc") { isMounted("proc") }
        mountIfNeeded("/sys", "--bind /sys") { isMounted("sys") }
        mountIfNeeded("/run", "-t tmpfs tmpfs") { isMounted("run") }
        mountIfNeeded("/tmp", "-t tmpfs tmpfs") { isMounted("tmp") }

        // Android's app-data filesystem is nosuid. Change the flag only on the
        // isolated rootfs bind (not its parent mount), addressing it as / from
        // inside the chroot so mount resolves the correct bind mount.
        if (execChroot("mount -o remount,bind,suid,dev /") != 0) {
            Log.w(TAG, "Could not enable privileged helpers on chroot bind mount")
        }

        // Fix missing symlinks in Android's /dev
        val devPath = File(rootfsDir, "dev").absolutePath
        rootShell.exec("ln -snf /proc/self/fd $devPath/fd")
        rootShell.exec("ln -snf /proc/self/fd/0 $devPath/stdin")
        rootShell.exec("ln -snf /proc/self/fd/1 $devPath/stdout")
        rootShell.exec("ln -snf /proc/self/fd/2 $devPath/stderr")

        // Grant app access to GPU for Termux:X11 DRI3
        rootShell.exec("chmod 666 /dev/dri/* 2>/dev/null")

        // Grant app access to input devices (/dev/input/event*) for low-latency touch/evdev handling
        rootShell.exec("chmod 666 /dev/input/* 2>/dev/null || true")

        // ALSA direct audio: make sound nodes accessible and configure default card
        if (NativOSPreferences.alsaDirectAudio(context)) {
            rootShell.exec("chmod 755 /dev/snd 2>/dev/null; chmod 666 /dev/snd/* 2>/dev/null || true")
            val asoundConf = File(rootfsDir, "etc/asound.conf")
            try {
                asoundConf.parentFile?.mkdirs()
                asoundConf.writeText(
                    """
                    # NativOS Direct ALSA Configuration (Qualcomm SoC)
                    pcm.!default {
                        type plug
                        slave.pcm "hw:0,0"
                    }
                    ctl.!default {
                        type hw
                        card 0
                    }
                    """.trimIndent()
                )
                Log.i(TAG, "Configured direct ALSA hardware audio (/etc/asound.conf)")
            } catch (e: Exception) {
                Log.w(TAG, "Could not write /etc/asound.conf: ${e.message}")
            }
        }

        // Hardware video decoding: grant access to Qualcomm Venus VPU and memory heaps
        if (NativOSPreferences.hardwareVideoDecoding(context)) {
            rootShell.exec("chmod 666 /dev/video* /dev/media* /dev/ion 2>/dev/null || true")
            Log.i(TAG, "Granted direct access to hardware video codecs (/dev/video*)")
        }

        // Auto-mount external USB OTG and MicroSD storage into chroot
        loopDeviceManager.mountExternalDrives(rootfsDir)

        // Create runtime directories inside chroot
        execChroot("mkdir -p /tmp/.X11-unix /tmp/runtime-root /run/nativOS /root")

        // Bind-mount bridge socket directory so Linux side can connect
        bridgeSocketDir.mkdirs()
        val chrootBridgeDir = File(rootfsDir, "run/nativOS").absolutePath
        if (!mounts.any { it.contains(" on $chrootBridgeDir ") }) {
            rootShell.exec("mkdir -p $chrootBridgeDir && mount --bind ${bridgeSocketDir.absolutePath} $chrootBridgeDir")
            Log.i(TAG, "Bound bridge socket dir into chroot")
        }

        // Bind-mount host files directory into chroot so absolute paths match
        val hostFilesDir = context.filesDir.absolutePath
        val chrootFilesDir = File(rootfsDir, hostFilesDir).absolutePath
        if (!mounts.any { it.contains(" on $chrootFilesDir ") }) {
            rootShell.exec("mkdir -p $chrootFilesDir && mount --bind $hostFilesDir $chrootFilesDir")
            Log.i(TAG, "Bound host files dir into chroot for path compatibility")
        }

        // Expose one deliberate app-owned folder. Android sees it through the
        // NativOS Shared documents provider; Linux sees /root/Shared.
        val chrootSharedDir = File(rootfsDir, "mnt/android").absolutePath
        androidSharedDir.mkdirs()
        // Rebind every session so upgrades migrate any stale /data/media mount.
        if (mounts.any { it.contains(" on $chrootSharedDir ") }) {
            rootShell.exec("umount -l $chrootSharedDir 2>/dev/null || true")
        }
        val result = rootShell.exec(
            "mkdir -p ${androidSharedDir.absolutePath} $chrootSharedDir && " +
                "chmod -R 0777 ${androidSharedDir.absolutePath} && " +
                "mount --bind ${androidSharedDir.absolutePath} $chrootSharedDir && " +
                "echo NATIVOS_SHARED_READY"
        )
        if (result.contains("NATIVOS_SHARED_READY")) {
            Log.i(TAG, "Bound Android shared folder into Linux")
        } else {
            Log.w(TAG, "Could not mount Android shared folder: ${result.trim()}")
        }
        execChroot("mkdir -p /mnt/android && ln -snf /mnt/android /root/Shared")

        // Refresh DNS
        try {
            File(rootfsDir, "etc/resolv.conf").writeText("nameserver 8.8.8.8\nnameserver 1.1.1.1\n")
        } catch (e: Exception) {
            Log.w(TAG, "Could not overwrite resolv.conf from Kotlin, it might be root-owned. Proceeding.")
        }

        Log.i(TAG, "All mounts ready")
    }

    /** Bind-mount the host X11 socket directory into the chroot so that
     *  phoc (running with WLR_BACKENDS=x11) can connect to the Termux X11
     *  server at DISPLAY=:0.  Also bind-mounts /tmp/runtime-root so that
     *  Wayland-based tooling can see any host Wayland socket. */
    fun bindX11Socket() {
        if (!hasRoot()) return
        val chrootX11 = File(rootfsDir, "tmp/.X11-unix").absolutePath
        val hostX11 = "/tmp/.X11-unix"

        val mounts = rootShell.exec("mount").lines()

        // Unmount any stale X11 bind if it points somewhere different.
        if (mounts.any { it.contains(" on $chrootX11 ") }) {
            rootShell.exec("umount $chrootX11 2>/dev/null || true")
        }

        // Create the target directory inside the chroot and bind-mount.
        rootShell.exec(
            "mkdir -p $chrootX11 && chmod 1777 $chrootX11 && " +
            "mount --bind $hostX11 $chrootX11"
        ).also {
            Log.i(TAG, "X11 socket bind-mounted: $hostX11 → $chrootX11")
        }
    }

    /** Install and validate the bundled KGSL Turnip driver on Adreno devices. */
    private fun prepareHardwareGpu(): Boolean {
        if (!File("/dev/kgsl-3d0").exists()) {
            Log.i(TAG, "No KGSL device; using portable software rendering")
            return false
        }

        return try {
            val archive = File(baseDir, "gpu/mesa-vulkan-drivers-kgsl-$TURNIP_VERSION-arm64.deb")
            if (!archive.exists() || sha256(archive) != TURNIP_SHA256) {
                archive.parentFile?.mkdirs()
                context.assets.open(TURNIP_ASSET).use { input ->
                    archive.outputStream().use { output -> input.copyTo(output) }
                }
            }
            if (sha256(archive) != TURNIP_SHA256) {
                Log.e(TAG, "Bundled Turnip archive failed checksum validation")
                return false
            }

            val library = File(turnipRoot, "usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so")
            if (!library.exists() || !turnipIcd.exists()) {
                val root = turnipRoot.absolutePath.removePrefix(rootfsDir.absolutePath)
                val archiveInChroot = archive.absolutePath
                val installCommand = """
                    mkdir -p $root &&
                    if command -v dpkg-deb >/dev/null 2>&1; then
                        dpkg-deb -x $archiveInChroot $root;
                    elif command -v apk >/dev/null 2>&1; then
                        (apk add --no-cache dpkg 2>/dev/null && dpkg-deb -x $archiveInChroot $root) || (cd $root && ar -x $archiveInChroot && tar -xf data.tar.*);
                    else
                        (cd $root && ar -x $archiveInChroot && tar -xf data.tar.*);
                    fi &&
                    sed -i 's#/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so#$root/usr/lib/aarch64-linux-gnu/libvulkan_freedreno.so#' $root/usr/share/vulkan/icd.d/freedreno_icd.aarch64.json
                """.trimIndent()
                if (execChroot(installCommand) != 0) {
                    Log.e(TAG, "Could not extract bundled Turnip driver")
                    return false
                }
            }

            val available = library.exists() && turnipIcd.exists()
            if (!available) {
                Log.w(TAG, "Turnip driver unavailable; using software")
                return false
            }

            val icd = turnipIcd.absolutePath.removePrefix(rootfsDir.absolutePath)
            val probe = "command -v vulkaninfo >/dev/null 2>&1 && " +
                "VK_ICD_FILENAMES=$icd TU_DEBUG=noconform " +
                "timeout 10s vulkaninfo --summary >/dev/null 2>&1"
            if (execChroot(probe) != 0) {
                Log.w(TAG, "Turnip Vulkan probe failed; using software rendering")
                return false
            }

            Log.i(TAG, "Turnip KGSL driver ready")
            true
        } catch (error: Throwable) {
            Log.w(TAG, "GPU setup failed; using software rendering", error)
            false
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Make modern GLib app launching work on kernels with partial close_range support. */
    private fun prepareCloseRangeCompatibility() {
        val library = File(rootfsDir, "usr/local/lib/libnativos-close-range.so")
        if (library.exists()) return

        try {
            val source = File(baseDir, "compat/close_range.c")
            source.parentFile?.mkdirs()
            source.writeText(
                """
                #define _GNU_SOURCE
                #include <dirent.h>
                #include <errno.h>
                #include <limits.h>
                #include <ftw.h>
                int close_range(unsigned int first, unsigned int last, int flags) {
                    (void) first; (void) last; (void) flags;
                    errno = ENOSYS;
                    return -1;
                }
                """.trimIndent()
            )
            val result = execChroot(
                "mkdir -p /usr/local/lib && " +
                    "gcc -shared -fPIC -o /usr/local/lib/libnativos-close-range.so ${source.absolutePath}"
            )
            if (result != 0) Log.w(TAG, "Could not build close_range compatibility shim")
        } catch (error: Throwable) {
            Log.w(TAG, "Could not prepare close_range compatibility shim", error)
        }
    }

    /** Ensure libnodri3.so blocks DRI3 safely without unresolved xcb_dri3_id symbol relocations. */
    private fun prepareNoDri3Hook() {
        val library = File(rootfsDir, "usr/local/lib/libnodri3.so")
        var needsRebuild = false
        if (library.exists()) {
            try {
                val bytes = library.readBytes()
                val content = String(bytes, Charsets.ISO_8859_1)
                if (content.contains("xcb_dri3_id")) {
                    Log.w(TAG, "libnodri3.so contains broken xcb_dri3_id relocation; removing it")
                    library.delete()
                    needsRebuild = true
                }
            } catch (e: Throwable) {
                Log.w(TAG, "Could not inspect libnodri3.so", e)
            }
        } else {
            needsRebuild = true
        }

        if (needsRebuild) {
            try {
                val source = File(baseDir, "compat/nodri3.c")
                source.parentFile?.mkdirs()
                source.writeText(
                    """
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
                            real_fn = dlsym(RTLD_NEXT, "xcb_get_extension_data");
                        }
                        struct my_xcb_extension_t *my_ext = (struct my_xcb_extension_t *)ext;
                        if (my_ext && my_ext->name && strcmp(my_ext->name, "DRI3") == 0) {
                            return NULL;
                        }
                        return real_fn ? real_fn(c, ext) : NULL;
                    }
                    """.trimIndent()
                )
                val result = execChroot(
                    "mkdir -p /usr/local/lib && " +
                        "(gcc -shared -fPIC -o /usr/local/lib/libnodri3.so ${source.absolutePath} -ldl 2>/dev/null || " +
                        "gcc -shared -fPIC -o /usr/local/lib/libnodri3.so ${source.absolutePath} -ldl -lxcb 2>/dev/null || true)"
                )
                if (result != 0) {
                    Log.w(TAG, "Could not build libnodri3 compatibility shim (omitting it)")
                    if (library.exists()) library.delete()
                } else {
                    Log.i(TAG, "Universal libnodri3 hook compiled successfully")
                }
            } catch (error: Throwable) {
                Log.w(TAG, "Could not prepare nodri3 hook", error)
            }
        }
    }

    /** Build a tiny XCB helper that keeps Phoc's nested X11 window in sync with Android. */
    private fun prepareDisplayResizeHelper() {
        val helper = File(rootfsDir, "usr/local/bin/nativos-resize-phoc")
        if (!helper.exists()) try {
            val source = File(baseDir, "compat/nativos_resize_phoc.c")
            source.parentFile?.mkdirs()
            source.writeText(
                """
                #include <stdint.h>
                #include <stdlib.h>
                #include <xcb/xcb.h>

                int main(int argc, char **argv) {
                    if (argc != 3) return 64;
                    long width = strtol(argv[1], 0, 10);
                    long height = strtol(argv[2], 0, 10);
                    if (width < 1 || height < 1 || width > 32767 || height > 32767) return 64;

                    xcb_connection_t *connection = xcb_connect(0, 0);
                    if (xcb_connection_has_error(connection)) return 69;
                    const xcb_setup_t *setup = xcb_get_setup(connection);
                    xcb_screen_t *screen = xcb_setup_roots_iterator(setup).data;

                    const char atom_name[] = "WM_CLASS";
                    xcb_intern_atom_reply_t *wm_class = xcb_intern_atom_reply(
                        connection,
                        xcb_intern_atom(connection, 0, sizeof(atom_name) - 1, atom_name),
                        0
                    );
                    xcb_query_tree_reply_t *tree = xcb_query_tree_reply(
                        connection, xcb_query_tree(connection, screen->root), 0
                    );
                    if (!tree || !wm_class) return 70;

                    int resized = 0;
                    int child_count = xcb_query_tree_children_length(tree);
                    xcb_window_t *children = xcb_query_tree_children(tree);
                    for (int index = 0; index < child_count; index++) {
                        xcb_get_window_attributes_reply_t *attributes =
                            xcb_get_window_attributes_reply(
                                connection,
                                xcb_get_window_attributes(connection, children[index]),
                                0
                            );
                        xcb_get_property_reply_t *window_class = xcb_get_property_reply(
                            connection,
                            xcb_get_property(
                                connection, 0, children[index], wm_class->atom,
                                XCB_GET_PROPERTY_TYPE_ANY, 0, 1
                            ),
                            0
                        );
                        if (attributes && attributes->map_state == XCB_MAP_STATE_VIEWABLE &&
                            window_class && xcb_get_property_value_length(window_class) == 0) {
                            uint32_t dimensions[] = {(uint32_t) width, (uint32_t) height};
                            xcb_configure_window(
                                connection, children[index],
                                XCB_CONFIG_WINDOW_WIDTH | XCB_CONFIG_WINDOW_HEIGHT,
                                dimensions
                            );
                            resized++;
                        }
                        free(attributes);
                        free(window_class);
                    }
                    xcb_flush(connection);
                    free(tree);
                    free(wm_class);
                    xcb_disconnect(connection);
                    return resized > 0 ? 0 : 2;
                }
                """.trimIndent()
            )
            val result = execChroot(
                "mkdir -p /usr/local/bin && " +
                    "gcc -O2 -o /usr/local/bin/nativos-resize-phoc ${source.absolutePath} -lxcb"
            )
            if (result != 0) Log.w(TAG, "Could not build Phoc display resize helper")
        } catch (error: Throwable) {
            Log.w(TAG, "Could not prepare Phoc display resize helper", error)
        }

        val maximizeHelper = File(rootfsDir, "usr/local/bin/nativos-maximize-x11-v6")
        if (maximizeHelper.exists()) return
        try {
            val source = File(baseDir, "compat/nativos_maximize_x11.c")
            source.parentFile?.mkdirs()
            source.writeText(
                """
                #include <stdint.h>
                #include <signal.h>
                #include <stdio.h>
                #include <stdlib.h>
                #include <string.h>
                #include <unistd.h>
                #include <xcb/xcb.h>

                static volatile sig_atomic_t requested_action = 0;
                static void request_home(int signal_number) {
                    (void) signal_number;
                    requested_action = 1;
                }
                static void request_restore(int signal_number) {
                    (void) signal_number;
                    requested_action = 2;
                }

                static int is_application(xcb_connection_t *connection, xcb_window_t window,
                                          xcb_atom_t wm_class) {
                    xcb_get_window_attributes_reply_t *attributes =
                        xcb_get_window_attributes_reply(
                            connection, xcb_get_window_attributes(connection, window), 0);
                    xcb_get_property_reply_t *window_class = xcb_get_property_reply(
                        connection,
                        xcb_get_property(connection, 0, window, wm_class,
                                         XCB_GET_PROPERTY_TYPE_ANY, 0, 64),
                        0);
                    int result = attributes && attributes->map_state == XCB_MAP_STATE_VIEWABLE &&
                                 window_class && xcb_get_property_value_length(window_class) > 0;
                    free(attributes);
                    free(window_class);
                    return result;
                }

                static xcb_window_t find_desktop(xcb_connection_t *connection,
                                                 xcb_screen_t *screen,
                                                 xcb_atom_t wm_class,
                                                 xcb_window_t excluded) {
                    xcb_window_t desktop = XCB_WINDOW_NONE;
                    uint32_t largest_area = 0;
                    xcb_query_tree_reply_t *tree = xcb_query_tree_reply(
                        connection, xcb_query_tree(connection, screen->root), 0);
                    if (!tree) return desktop;
                    int count = xcb_query_tree_children_length(tree);
                    xcb_window_t *children = xcb_query_tree_children(tree);
                    for (int index = 0; index < count; index++) {
                        xcb_window_t child = children[index];
                        if (child == excluded) continue;
                        xcb_get_window_attributes_reply_t *attributes =
                            xcb_get_window_attributes_reply(
                                connection, xcb_get_window_attributes(connection, child), 0);
                        xcb_get_property_reply_t *window_class = xcb_get_property_reply(
                            connection,
                            xcb_get_property(connection, 0, child, wm_class,
                                             XCB_GET_PROPERTY_TYPE_ANY, 0, 64), 0);
                        xcb_get_geometry_reply_t *geometry = xcb_get_geometry_reply(
                            connection, xcb_get_geometry(connection, child), 0);
                        if (attributes && geometry && window_class &&
                            attributes->map_state == XCB_MAP_STATE_VIEWABLE &&
                            xcb_get_property_value_length(window_class) == 0) {
                            uint32_t area = geometry->width * geometry->height;
                            if (area > largest_area) {
                                largest_area = area;
                                desktop = child;
                            }
                        }
                        free(attributes);
                        free(window_class);
                        free(geometry);
                    }
                    free(tree);
                    return desktop;
                }

                int main(int argc, char **argv) {
                    xcb_connection_t *connection = xcb_connect(0, 0);
                    if (xcb_connection_has_error(connection)) return 69;
                    xcb_screen_t *screen = xcb_setup_roots_iterator(xcb_get_setup(connection)).data;

                    const char atom_name[] = "WM_CLASS";
                    xcb_intern_atom_reply_t *wm_class = xcb_intern_atom_reply(
                        connection,
                        xcb_intern_atom(connection, 0, sizeof(atom_name) - 1, atom_name), 0);
                    if (!wm_class) return 70;

                    if (argc == 2 && strcmp(argv[1], "--focus-desktop") == 0) {
                        xcb_window_t desktop = find_desktop(
                            connection, screen, wm_class->atom, XCB_WINDOW_NONE);
                        if (desktop == XCB_WINDOW_NONE) {
                            free(wm_class);
                            xcb_disconnect(connection);
                            return 2;
                        }
                        uint32_t above = XCB_STACK_MODE_ABOVE;
                        xcb_configure_window(connection, desktop,
                                             XCB_CONFIG_WINDOW_STACK_MODE, &above);
                        xcb_set_input_focus(connection, XCB_INPUT_FOCUS_POINTER_ROOT,
                                            desktop, XCB_CURRENT_TIME);
                        xcb_flush(connection);
                        free(wm_class);
                        xcb_disconnect(connection);
                        return 0;
                    }

                    xcb_window_t target = XCB_WINDOW_NONE;
                    xcb_get_input_focus_reply_t *focus = xcb_get_input_focus_reply(
                        connection, xcb_get_input_focus(connection), 0);
                    if (focus && focus->focus != XCB_WINDOW_NONE && focus->focus != screen->root) {
                        xcb_window_t current = focus->focus;
                        while (current != screen->root) {
                            xcb_query_tree_reply_t *tree = xcb_query_tree_reply(
                                connection, xcb_query_tree(connection, current), 0);
                            if (!tree) break;
                            xcb_window_t parent = tree->parent;
                            free(tree);
                            if (parent == screen->root) {
                                if (is_application(connection, current, wm_class->atom)) target = current;
                                break;
                            }
                            current = parent;
                        }
                    }
                    free(focus);

                    /* If an Android overlay disturbed focus, use the top-most mapped app. */
                    if (target == XCB_WINDOW_NONE) {
                        xcb_query_tree_reply_t *tree = xcb_query_tree_reply(
                            connection, xcb_query_tree(connection, screen->root), 0);
                        if (tree) {
                            int count = xcb_query_tree_children_length(tree);
                            xcb_window_t *children = xcb_query_tree_children(tree);
                            for (int index = count - 1; index >= 0; index--) {
                                if (is_application(connection, children[index], wm_class->atom)) {
                                    target = children[index];
                                    break;
                                }
                            }
                            free(tree);
                        }
                    }

                    if (target == XCB_WINDOW_NONE) {
                        free(wm_class);
                        xcb_disconnect(connection);
                        return 2;
                    }

                    xcb_get_geometry_reply_t *original = xcb_get_geometry_reply(
                        connection, xcb_get_geometry(connection, target), 0);
                    if (!original) {
                        free(wm_class);
                        xcb_disconnect(connection);
                        return 70;
                    }

                    uint32_t geometry[] = {0, 0, screen->width_in_pixels,
                                           screen->height_in_pixels, 0};
                    /*
                     * With no X11 WM, resizing a root child leaves stale backing pixels.
                     * Reparent it into a clean full-screen frame and keep that frame alive
                     * until the application closes.
                     */
                    xcb_unmap_window(connection, target);
                    xcb_window_t frame = xcb_generate_id(connection);
                    uint32_t frame_values[] = {
                        screen->black_pixel,
                        XCB_EVENT_MASK_EXPOSURE | XCB_EVENT_MASK_SUBSTRUCTURE_NOTIFY
                    };
                    xcb_create_window(
                        connection, screen->root_depth, frame, screen->root,
                        0, 0, screen->width_in_pixels, screen->height_in_pixels, 0,
                        XCB_WINDOW_CLASS_INPUT_OUTPUT, screen->root_visual,
                        XCB_CW_BACK_PIXEL | XCB_CW_EVENT_MASK, frame_values);
                    xcb_change_save_set(connection, XCB_SET_MODE_INSERT, target);
                    xcb_reparent_window(connection, target, frame, 0, 0);
                    xcb_configure_window(
                        connection, target,
                        XCB_CONFIG_WINDOW_X | XCB_CONFIG_WINDOW_Y |
                        XCB_CONFIG_WINDOW_WIDTH | XCB_CONFIG_WINDOW_HEIGHT |
                        XCB_CONFIG_WINDOW_BORDER_WIDTH,
                        geometry);
                    uint32_t child_background = screen->black_pixel;
                    xcb_change_window_attributes(
                        connection, target, XCB_CW_BACK_PIXEL, &child_background);
                    xcb_map_window(connection, frame);
                    xcb_map_window(connection, target);
                    xcb_clear_area(connection, 1, target, 0, 0, 0, 0);
                    xcb_set_input_focus(connection, XCB_INPUT_FOCUS_POINTER_ROOT,
                                        target, XCB_CURRENT_TIME);
                    xcb_flush(connection);

                    signal(SIGUSR1, request_home);
                    signal(SIGTERM, request_restore);
                    signal(SIGINT, request_restore);
                    FILE *pid_file = fopen("/run/nativOS/x11-fullscreen.pid", "w");
                    if (pid_file) {
                        fprintf(pid_file, "%ld\n", (long) getpid());
                        fclose(pid_file);
                    }

                    int target_destroyed = 0;
                    while (!requested_action && !xcb_connection_has_error(connection)) {
                        xcb_generic_event_t *event;
                        while ((event = xcb_poll_for_event(connection)) != 0) {
                            uint8_t type = event->response_type & 0x7f;
                            if (type == XCB_DESTROY_NOTIFY) {
                                xcb_destroy_notify_event_t *destroyed =
                                    (xcb_destroy_notify_event_t *) event;
                                if (destroyed->window == target) target_destroyed = 1;
                            }
                            free(event);
                        }
                        if (target_destroyed) break;
                        usleep(20000);
                    }

                    if (!target_destroyed && requested_action) {
                        xcb_unmap_window(connection, target);
                        xcb_reparent_window(connection, target, screen->root,
                                            original->x, original->y);
                        uint32_t restored[] = {
                            (uint32_t) original->x, (uint32_t) original->y,
                            original->width, original->height, original->border_width
                        };
                        xcb_configure_window(
                            connection, target,
                            XCB_CONFIG_WINDOW_X | XCB_CONFIG_WINDOW_Y |
                            XCB_CONFIG_WINDOW_WIDTH | XCB_CONFIG_WINDOW_HEIGHT |
                            XCB_CONFIG_WINDOW_BORDER_WIDTH,
                            restored);
                        xcb_destroy_window(connection, frame);

                        if (requested_action == 1) {
                            /* Home minimizes the unmanaged app so Phosh is unobscured. */
                            xcb_window_t desktop = find_desktop(
                                connection, screen, wm_class->atom, frame);
                            if (desktop != XCB_WINDOW_NONE) {
                                uint32_t above = XCB_STACK_MODE_ABOVE;
                                xcb_configure_window(connection, desktop,
                                                     XCB_CONFIG_WINDOW_STACK_MODE, &above);
                                xcb_set_input_focus(connection, XCB_INPUT_FOCUS_POINTER_ROOT,
                                                    desktop, XCB_CURRENT_TIME);
                            }
                        } else {
                            xcb_map_window(connection, target);
                            xcb_clear_area(connection, 1, target, 0, 0, 0, 0);
                            xcb_set_input_focus(connection, XCB_INPUT_FOCUS_POINTER_ROOT,
                                                target, XCB_CURRENT_TIME);
                        }
                        xcb_flush(connection);
                    }
                    unlink("/run/nativOS/x11-fullscreen.pid");
                    free(original);
                    free(wm_class);
                    xcb_disconnect(connection);
                    return 0;
                }
                """.trimIndent()
            )
            val result = execChroot(
                "mkdir -p /usr/local/bin && " +
                    "gcc -O2 -o /usr/local/bin/nativos-maximize-x11-v6 ${source.absolutePath} -lxcb"
            )
            if (result != 0) Log.w(TAG, "Could not build X11 maximize helper")
        } catch (error: Throwable) {
            Log.w(TAG, "Could not prepare X11 maximize helper", error)
        }
    }

    /** Resize only Phoc's undecorated outer X11 window; ordinary X11 apps are untouched. */
    fun resizePhoshDisplay(width: Int, height: Int): Boolean {
        if (width <= 0 || height <= 0 || !isRunning()) return false
        val helper = File(rootfsDir, "usr/local/bin/nativos-resize-phoc")
        if (!helper.exists()) return false
        val result = execChroot(
            "TMPDIR=${tmpDir.absolutePath} DISPLAY=:0 " +
                "LD_PRELOAD=/usr/local/lib/libsocket_hook.so " +
                "/usr/local/bin/nativos-resize-phoc $width $height"
        )
        if (result == 0) {
            Log.i(TAG, "Resized Phoc X11 output to ${width}x${height}")
            return true
        }
        Log.w(TAG, "Phoc X11 output resize failed (exit $result)")
        return false
    }

    /** Force the focused unmanaged X11 application to occupy the complete Linux display. */
    fun maximizeActiveX11Window(): Boolean {
        if (!isRunning()) return false
        prepareDisplayResizeHelper()
        val helper = File(rootfsDir, "usr/local/bin/nativos-maximize-x11-v6")
        if (!helper.exists()) return false
        return execChroot(
            "TMPDIR=${tmpDir.absolutePath} DISPLAY=:0 " +
                "LD_PRELOAD=/usr/local/lib/libsocket_hook.so " +
                "/usr/local/bin/nativos-maximize-x11-v6"
        ) == 0
    }

    /** Raise Phoc's outer X11 window so subsequent input reaches the Wayland desktop. */
    fun focusPhoshWindow(): Boolean {
        prepareDisplayResizeHelper()
        val helper = File(rootfsDir, "usr/local/bin/nativos-maximize-x11-v6")
        if (!helper.exists()) return false
        return execChroot(
            "TMPDIR=${tmpDir.absolutePath} DISPLAY=:0 " +
                "LD_PRELOAD=/usr/local/lib/libsocket_hook.so " +
                "/usr/local/bin/nativos-maximize-x11-v6 --focus-desktop"
        ) == 0
    }

    /** Leave forced X11 fullscreen and optionally return keyboard focus to Phosh. */
    fun restoreMaximizedX11Window(focusDesktop: Boolean): Boolean {
        val pidFile = File(rootfsDir, "run/nativOS/x11-fullscreen.pid")
        if (!pidFile.exists()) return false
        val signal = if (focusDesktop) "USR1" else "TERM"
        return execChroot(
            "test -s /run/nativOS/x11-fullscreen.pid && " +
                "kill -$signal \"${'$'}(cat /run/nativOS/x11-fullscreen.pid)\""
        ) == 0
    }

    /**
     * Use Bubblewrap's privileged fallback on Android kernels that disable
     * unprivileged user namespaces. Android mounts app data with nosuid, so a
     * small setuid launcher prepares the mount sandbox, then drops the actual
     * application and D-Bus proxy to a locked UID. The original distro binary
     * remains preserved.
     */
    private fun prepareFlatpakCompatibility() {
        val distroBwrap = File(rootfsDir, "usr/bin/bwrap")
        val installedWrapper = File(rootfsDir, "usr/local/bin/bwrap")
        if (!distroBwrap.exists() && !installedWrapper.exists()) return
        val sandboxUid = context.applicationInfo.uid

        try {
            val source = File(baseDir, "compat/nativos_bwrap.c")
            source.parentFile?.mkdirs()
            source.writeText(
                """
                #define _GNU_SOURCE
                #include <dirent.h>
                #include <errno.h>
                #include <fcntl.h>
                #include <limits.h>
                #include <stdio.h>
                #include <stdlib.h>
                #include <string.h>
                #include <sys/mman.h>
                #include <sys/stat.h>
                #include <sys/types.h>
                #include <unistd.h>

                static int unsupported_namespace(const char *value) {
                    return strcmp(value, "--unshare-pid") == 0 ||
                           strcmp(value, "--unshare-ipc") == 0;
                }

                static int bind_option(const char *value) {
                    return strcmp(value, "--bind") == 0 ||
                           strcmp(value, "--ro-bind") == 0 ||
                           strcmp(value, "--dev-bind") == 0 ||
                           strcmp(value, "--bind-try") == 0 ||
                           strcmp(value, "--ro-bind-try") == 0 ||
                           strcmp(value, "--dev-bind-try") == 0;
                }

                static int unavailable_runtime_bind(const char *source) {
                    return strncmp(source, "/tmp/runtime-root/doc/", 22) == 0 ||
                           strncmp(source, "/tmp/runtime-root/.flatpak-helper/", 34) == 0;
                }

                static void run_flatpak_ldconfig_directly(int argc, char **argv) {
                    for (int input = 1; input + 2 < argc; input++) {
                        const char *command = strrchr(argv[input], '/');
                        command = command == NULL ? argv[input] : command + 1;
                        if (strcmp(command, "ldconfig") != 0) continue;
                        for (int option = input + 1; option + 1 < argc; option++) {
                            if (strcmp(argv[option], "-C") != 0) continue;
                            const char *cache = argv[option + 1];
                            if (strncmp(cache, "/run/ld-so-cache-dir/", 21) != 0 ||
                                strstr(cache, "..") != NULL) return;
                            execl("/sbin/ldconfig", "ldconfig", "-X", "-C", cache, NULL);
                            perror("nativos-bwrap: ldconfig");
                            _exit(1);
                        }
                    }
                }

                static void make_runtime_parents_traversable(const char *source) {
                    const char *prefix = "/tmp/runtime-root/";
                    if (strncmp(source, prefix, 18) != 0) return;
                    char *path = strdup(source);
                    if (path == NULL) return;
                    for (char *cursor = path + 18; (cursor = strchr(cursor, '/')) != NULL; cursor++) {
                        *cursor = '\0';
                        chmod(path, 0711);
                        *cursor = '/';
                    }
                    free(path);
                }

                static void make_root_parents_traversable(const char *source) {
                    if (strncmp(source, "/root/", 6) != 0) return;
                    char *path = strdup(source);
                    if (path == NULL) return;
                    for (char *cursor = path + 6; (cursor = strchr(cursor, '/')) != NULL; cursor++) {
                        *cursor = '\0';
                        chmod(path, 0755);
                        *cursor = '/';
                    }
                    free(path);
                }

                static void make_runtime_tree_traversable(const char *path, int depth) {
                    if (depth > 12) return;
                    chmod(path, 0711);
                    DIR *directory = opendir(path);
                    if (directory == NULL) return;
                    struct dirent *entry;
                    while ((entry = readdir(directory)) != NULL) {
                        if (strcmp(entry->d_name, ".") == 0 ||
                            strcmp(entry->d_name, "..") == 0) continue;
                        char child[PATH_MAX];
                        if (snprintf(child, sizeof(child), "%s/%s", path, entry->d_name) >=
                            (int) sizeof(child)) continue;
                        struct stat info;
                        if (lstat(child, &info) == 0 && S_ISDIR(info.st_mode)) {
                            make_runtime_tree_traversable(child, depth + 1);
                        }
                    }
                    closedir(directory);
                }

                static void make_app_tree_writable(const char *path, int depth,
                                                   uid_t sandbox_uid) {
                    if (depth > 24) return;
                    struct stat info;
                    if (lstat(path, &info) != 0 || S_ISLNK(info.st_mode)) return;
                    lchown(path, sandbox_uid, 0);
                    mode_t mode = info.st_mode | S_IRUSR | S_IWUSR;
                    if (S_ISDIR(info.st_mode)) mode |= S_IXUSR;
                    chmod(path, mode);
                    if (!S_ISDIR(info.st_mode)) return;
                    DIR *directory = opendir(path);
                    if (directory == NULL) return;
                    struct dirent *entry;
                    while ((entry = readdir(directory)) != NULL) {
                        if (strcmp(entry->d_name, ".") == 0 ||
                            strcmp(entry->d_name, "..") == 0) continue;
                        char child[PATH_MAX];
                        if (snprintf(child, sizeof(child), "%s/%s", path, entry->d_name) >=
                            (int) sizeof(child)) continue;
                        make_app_tree_writable(child, depth + 1, sandbox_uid);
                    }
                    closedir(directory);
                }

                static void prepare_writable_bind(const char *source,
                                                  const char *destination,
                                                  uid_t sandbox_uid) {
                    if (strcmp(destination, "/app/extra") == 0 ||
                        strcmp(destination, "/run/ld-so-cache-dir") == 0 ||
                        strncmp(source, "/root/.var/app/", 15) == 0 ||
                        strncmp(source, "/root/.config/", 14) == 0 ||
                        strncmp(source, "/tmp/runtime-root/", 18) == 0) {
                        int ownership_result = chown(source, sandbox_uid, 0);
                        (void) ownership_result;
                        struct stat info;
                        chmod(source,
                              lstat(source, &info) == 0 && S_ISDIR(info.st_mode) ? 0775 : 0664);
                    }
                    if (strncmp(source, "/root/.var/app/", 15) == 0) {
                        make_app_tree_writable(source, 0, sandbox_uid);
                    }
                    make_runtime_parents_traversable(source);
                    make_root_parents_traversable(source);
                }

                static int filtered_args_fd(int source_fd, uid_t sandbox_uid) {
                    off_t size = lseek(source_fd, 0, SEEK_END);
                    if (size < 0 || lseek(source_fd, 0, SEEK_SET) < 0) return -1;

                    char *buffer = malloc((size_t) size);
                    if (buffer == NULL) return -1;
                    size_t received = 0;
                    while (received < (size_t) size) {
                        ssize_t count = read(source_fd, buffer + received, (size_t) size - received);
                        if (count <= 0) {
                            free(buffer);
                            return -1;
                        }
                        received += (size_t) count;
                    }
                    int target_fd = memfd_create("nativos-bwrap-args", 0);
                    if (target_fd < 0) {
                        free(buffer);
                        return -1;
                    }
                    size_t offset = 0;
                    while (offset < received) {
                        size_t remaining = received - offset;
                        size_t length = strnlen(buffer + offset, remaining);
                        if (length == remaining) break;
                        if (bind_option(buffer + offset)) {
                            size_t source_offset = offset + length + 1;
                            if (source_offset < received) {
                                size_t source_length = strnlen(
                                    buffer + source_offset, received - source_offset);
                                size_t destination_offset = source_offset + source_length + 1;
                                if (source_length < received - source_offset &&
                                    destination_offset < received) {
                                    size_t destination_length = strnlen(
                                        buffer + destination_offset, received - destination_offset);
                                    if (destination_length < received - destination_offset) {
                                        if (unavailable_runtime_bind(buffer + source_offset) ||
                                            strcmp(buffer + destination_offset,
                                                   "/run/host/monitor") == 0) {
                                            offset = destination_offset + destination_length + 1;
                                            continue;
                                        }
                                        prepare_writable_bind(
                                            buffer + source_offset,
                                            buffer + destination_offset,
                                            sandbox_uid);
                                    }
                                }
                            }
                        }
                        if (strcmp(buffer + offset, "0600") == 0 &&
                            offset + length + 1 < received) {
                            size_t action_offset = offset + length + 1;
                            size_t action_length = strnlen(
                                buffer + action_offset, received - action_offset);
                            size_t fd_offset = action_offset + action_length + 1;
                            if ((strcmp(buffer + action_offset, "--file") == 0 ||
                                 strcmp(buffer + action_offset, "--ro-bind-data") == 0) &&
                                action_length < received - action_offset &&
                                fd_offset < received) {
                                size_t fd_length = strnlen(
                                    buffer + fd_offset, received - fd_offset);
                                size_t destination_offset = fd_offset + fd_length + 1;
                                if (fd_length < received - fd_offset &&
                                    destination_offset < received) {
                                    size_t destination_length = strnlen(
                                        buffer + destination_offset,
                                        received - destination_offset);
                                    if (destination_length < received - destination_offset &&
                                        strcmp(buffer + destination_offset,
                                               "/.flatpak-info") == 0) {
                                        static const char readable[] = "0644";
                                        if (write(target_fd, readable, sizeof(readable)) !=
                                            (ssize_t) sizeof(readable)) {
                                            close(target_fd);
                                            free(buffer);
                                            return -1;
                                        }
                                        offset += length + 1;
                                        continue;
                                    }
                                }
                            }
                        }
                        if (strcmp(buffer + offset, "--ro-bind-data") == 0 &&
                            offset + length + 1 < received) {
                            size_t fd_offset = offset + length + 1;
                            size_t fd_length = strnlen(
                                buffer + fd_offset, received - fd_offset);
                            size_t destination_offset = fd_offset + fd_length + 1;
                            if (fd_length < received - fd_offset &&
                                destination_offset < received) {
                                size_t destination_length = strnlen(
                                    buffer + destination_offset,
                                    received - destination_offset);
                                if (destination_length < received - destination_offset &&
                                    strcmp(buffer + destination_offset,
                                           "/.flatpak-info") == 0) {
                                    static const char permissions[] = "--perms";
                                    static const char readable[] = "0644";
                                    if (write(target_fd, permissions, sizeof(permissions)) !=
                                            (ssize_t) sizeof(permissions) ||
                                        write(target_fd, readable, sizeof(readable)) !=
                                            (ssize_t) sizeof(readable)) {
                                        close(target_fd);
                                        free(buffer);
                                        return -1;
                                    }
                                }
                            }
                        }
                        if (!unsupported_namespace(buffer + offset) &&
                            write(target_fd, buffer + offset, length + 1) != (ssize_t) (length + 1)) {
                            close(target_fd);
                            free(buffer);
                            return -1;
                        }
                        offset += length + 1;
                    }
                    free(buffer);
                    if (lseek(target_fd, 0, SEEK_SET) < 0) {
                        close(target_fd);
                        return -1;
                    }
                    return target_fd;
                }

                int main(int argc, char **argv) {
                    const char *program_name = strrchr(argv[0], '/');
                    program_name = program_name == NULL ? argv[0] : program_name + 1;
                    if (strcmp(program_name, "nativos-flatpak-drop") == 0) {
                        chmod("/", 0755);
                        chmod("/etc", 0755);
                        chmod("/usr", 0755);
                        // Flatpak replaces parts of /root with private mounts.
                        // Allow the Android app UID to traverse GIMP and other
                        // applications' per-user configuration directories.
                        chmod("/root", 0755);
                        chmod("/root/.config", 0755);
                        chmod("/root/.var", 0755);
                        chmod("/root/.var/app", 0755);
                        chmod("/tmp", 01777);
                        chmod("/dev/shm", 01777);
                        chmod("/run", 0755);
                        chmod("/run/dbus", 0755);
                        chmod("/run/flatpak", 0755);
                        chmod("/run/user", 0755);
                        chmod("/run/user/0", 0700);
                        chmod("/.flatpak-info", 0644);
                        if (argc < 2 || setgid($sandboxUid) != 0 || setuid($sandboxUid) != 0) {
                            perror("nativos-flatpak-drop");
                            return 1;
                        }
                        execvp(argv[1], argv + 1);
                        perror("nativos-flatpak-drop: execvp");
                        return errno == ENOENT ? 127 : 126;
                    }

                    // Flatpak generates a runtime linker cache through a tiny
                    // Bubblewrap sandbox. That helper deadlocks on some Android
                    // kernels. Generate the same cache from the chroot; apps use
                    // the explicit runtime LD_LIBRARY_PATH added below.
                    run_flatpak_ldconfig_directly(argc, argv);

                    uid_t sandbox_uid = getuid() == 0 ? $sandboxUid : getuid();
                    if (geteuid() == 0) {
                        // The desktop currently stores its per-user Flatpaks
                        // below /root. Let the sandbox UID traverse the runtime,
                        // and own the one writable apply-extra destination.
                        chmod("/root", 0755);
                        chmod("/root/.local/share/flatpak", 0755);
                        chmod("/tmp", 01777);
                        make_runtime_tree_traversable("/tmp/runtime-root", 0);
                        for (int input = 1; input + 2 < argc; input++) {
                            if (bind_option(argv[input])) {
                                prepare_writable_bind(
                                    argv[input + 1], argv[input + 2], sandbox_uid);
                            }
                        }
                    }
                    // Several Android kernels disable PID and IPC namespaces.
                    // Flatpak remains isolated by Bubblewrap's mount namespace;
                    // omit only the flags the kernel explicitly cannot create.
                    char **filtered = calloc((size_t) argc + 96, sizeof(char *));
                    if (filtered == NULL) {
                        perror("nativos-bwrap: calloc");
                        return 1;
                    }
                    int output = 0;
                    for (int input = 0; input < argc; input++) {
                        if (bind_option(argv[input]) && input + 2 < argc &&
                            unavailable_runtime_bind(argv[input + 1])) {
                            input += 2;
                            continue;
                        }
                        if (unsupported_namespace(argv[input])) {
                            continue;
                        }
                        if (geteuid() == 0 && strcmp(argv[input], "--") == 0) {
                            // Android cannot create the user namespace required
                            // by bwrap's --uid option. Bind this launcher into the
                            // completed mount sandbox and drop IDs immediately
                            // before the application process is executed instead.
                            filtered[output++] = "--ro-bind";
                            filtered[output++] = "/usr/local/bin/bwrap";
                            filtered[output++] = "/run/nativos-flatpak-drop";
                            filtered[output++] = "--dir";
                            filtered[output++] = "/run/nativos";
                            filtered[output++] = "--ro-bind-try";
                            filtered[output++] = "/usr/local/lib/libsocket_hook.so";
                            filtered[output++] = "/run/nativos/libsocket_hook.so";
                            filtered[output++] = "--ro-bind-try";
                            filtered[output++] = "/usr/local/lib/libnativos-close-range.so";
                            filtered[output++] = "/run/nativos/libnativos-close-range.so";
                            filtered[output++] = "--setenv";
                            filtered[output++] = "LD_PRELOAD";
                            filtered[output++] = "/run/nativos/libsocket_hook.so:/run/nativos/libnativos-close-range.so";
                            filtered[output++] = "--setenv";
                            filtered[output++] = "ZYPAK_LD_PRELOAD";
                            filtered[output++] = "/run/nativos/libsocket_hook.so:/run/nativos/libnativos-close-range.so";
                            filtered[output++] = "--setenv";
                            filtered[output++] = "TMPDIR";
                            filtered[output++] = "${tmpDir.absolutePath}";
                            // Flatpak's generated ld cache is unreliable on Android's
                            // kernel/mount layout. Supply the application, runtime and
                            // Mesa extension paths directly so Store apps can start.
                            filtered[output++] = "--setenv";
                            filtered[output++] = "LD_LIBRARY_PATH";
                            filtered[output++] = "/app/lib:/app/lib/aarch64-linux-gnu:/usr/lib/aarch64-linux-gnu/GL/default/lib:/usr/lib/aarch64-linux-gnu:/usr/lib";
                            filtered[output++] = "--setenv";
                            filtered[output++] = "__EGL_VENDOR_LIBRARY_DIRS";
                            filtered[output++] = "/usr/lib/aarch64-linux-gnu/GL/default/share/glvnd/egl_vendor.d";
                            // The nested Android X11 display cannot expose a usable
                            // DRI fd inside Bubblewrap. Softpipe is slower but reliable
                            // and makes GTK, Qt and Flutter Flatpaks render everywhere.
                            filtered[output++] = "--setenv";
                            filtered[output++] = "LIBGL_ALWAYS_SOFTWARE";
                            filtered[output++] = "1";
                            filtered[output++] = "--setenv";
                            filtered[output++] = "GALLIUM_DRIVER";
                            filtered[output++] = "softpipe";
                            filtered[output++] = "--setenv";
                            filtered[output++] = "MESA_LOADER_DRIVER_OVERRIDE";
                            filtered[output++] = "swrast";
                            filtered[output++] = "--setenv";
                            filtered[output++] = "FONTCONFIG_FILE";
                            filtered[output++] = "/etc/fonts/fonts.conf";
                        }
                        if (strcmp(argv[input], "--args") == 0 && input + 1 < argc) {
                            int source_fd = atoi(argv[input + 1]);
                            int target_fd = filtered_args_fd(source_fd, sandbox_uid);
                            if (target_fd < 0) {
                                perror("nativos-bwrap: filter args");
                                return 1;
                            }
                            char *fd_value = malloc(24);
                            if (fd_value == NULL) return 1;
                            snprintf(fd_value, 24, "%d", target_fd);
                            filtered[output++] = argv[input];
                            filtered[output++] = fd_value;
                            input++;
                            continue;
                        }
                        filtered[output++] = argv[input];
                        if (geteuid() == 0 && strcmp(argv[input], "--") == 0) {
                            filtered[output++] = "/run/nativos-flatpak-drop";
                        }
                    }
                    filtered[output] = NULL;

                    execv("/usr/bin/bwrap", filtered);
                    perror("nativos-bwrap: execv");
                    return errno == ENOENT ? 127 : 126;
                }
                """.trimIndent()
            )
            val result = execChroot(
                "mkdir -p /usr/local/bin && " +
                    "gcc -O2 -o /usr/local/bin/bwrap ${source.absolutePath} && " +
                    "chown root:root /usr/local/bin/bwrap && chmod 4755 /usr/local/bin/bwrap && " +
                    "/usr/local/bin/bwrap --ro-bind / / --proc /proc --dev /dev /bin/true"
            )
            if (result == 0) {
                Log.i(TAG, "Flatpak Bubblewrap compatibility ready")
            } else {
                Log.w(TAG, "Could not prepare Flatpak Bubblewrap compatibility (exit $result)")
            }
        } catch (error: Throwable) {
            Log.w(TAG, "Could not prepare Flatpak Bubblewrap compatibility", error)
        }
    }

    // ── Session management ──

    /**
     * Start the Phosh desktop session inside the chroot.
     * This launches phoc (Wayland compositor) which hosts Phosh.
     *
     * @param screenWidth  The device's real screen width in pixels
     * @param screenHeight The device's real screen height in pixels
     */
    @Synchronized
    fun startPhoshSession(screenWidth: Int = 1080, screenHeight: Int = 2160) {
        if (!hasRoot()) {
            Log.e(TAG, "Cannot start session without root")
            return
        }
        if (!isRootfsReady()) {
            Log.e(TAG, "Rootfs not ready")
            return
        }
        if (isRunning()) Log.w(TAG, "Replacing existing tracked session")
        stopTrackedSessionProcess()
        killRootfsProcesses()

        ensureMounts()
        bindX11Socket()
        NativOSInit.installTo(rootfsDir)
        prepareCloseRangeCompatibility()
        prepareDisplayResizeHelper()
        prepareNoDri3Hook()
        prepareFlatpakCompatibility()

        val hardwareGpu = prepareHardwareGpu()
        val displayScale = DisplayController.computeScale(context)
        val resolvedGpu = GpuDetector.resolveEffectiveDriver(context)
        val gpuMode = if (resolvedGpu == "turnip" && !hardwareGpu) "software" else resolvedGpu
        val shell = if (File(rootfsDir, "usr/bin/bash").exists() || File(rootfsDir, "bin/bash").exists()) "/bin/bash" else "/bin/sh"
        val desktopEnv = NativOSPreferences.desktopEnvironment(context)
        val rotation = NativOSPreferences.touchRotationCorrection(context)
        val transformStr = when (rotation) {
            90 -> "90"
            180 -> "180"
            270 -> "270"
            else -> "normal"
        }
        val wlrRenderer = NativOSPreferences.wlrRenderer(context)
        val mesaGlThread = NativOSPreferences.mesaGlThread(context)
        val directDrm = NativOSPreferences.directDrmMode(context)
        if (directDrm) {
            Log.i(TAG, "Stopping SurfaceFlinger for Native Direct DRM session (with Safety Watchdog)...")
            SurfaceFlingerWatchdog.armWatchdog(context, rootShell, timeoutMs = 8_000L)
            rootShell.exec("stop surfaceflinger")
        }
        val initCmd = "${NativOSInit.SCRIPT_PATH} start --width $screenWidth --height $screenHeight --scale $displayScale --gpu $gpuMode --desktop $desktopEnv --tmpdir ${tmpDir.absolutePath} --app-uid ${context.applicationInfo.uid} --transform $transformStr --wlr-renderer $wlrRenderer --mesa-glthread $mesaGlThread --direct-drm $directDrm"

        Log.i(TAG, "Starting $desktopEnv session via nativOS-init (display: ${screenWidth}x$screenHeight @ scale $displayScale, GPU: $gpuMode, renderer: $wlrRenderer, glthread: $mesaGlThread, directDrm: $directDrm)")

        val su = rootShell.findSuPath() ?: return
        val fullCommand = "chroot ${rootfsDir.absolutePath} /usr/bin/env -i $shell -c ${shellQuote(initCmd)}"
        val startedSession = ProcessBuilder(su, "-c", fullCommand)
            .redirectErrorStream(true)
            .start()
        sessionProcess = startedSession

        // Apply high performance profile (CPU/GPU lock, VM tuning, and OOM killer protection)
        performanceManager.applyPerformanceProfile()
        performanceManager.protectCurrentSession()

        // Log output from the session
        Thread {
            try {
                val reader = startedSession.inputStream.bufferedReader()
                val buffer = CharArray(1024)
                var charsRead: Int
                while (reader.read(buffer).also { charsRead = it } != -1) {
                    Log.d(TAG, "SESSION: " + String(buffer, 0, charsRead))
                }
            } catch (error: java.io.IOException) {
                Log.d(TAG, "Session output stream closed")
            }
        }.start()
    }

    /**
     * Wait until the desktop session process is running and responsive.
     *
     * Detection strategy (in order):
     *  1. Wayland socket visible inside chroot + DE process running (ideal path)
     *  2. X11 socket visible inside chroot + DE process running (X11-nested path)
     *  3. DE process running alone — socket may not be visible from chroot view
     *     because the X11 server lives in the Android side; treat as ready after
     *     an extra stabilisation delay.
     *
     * The [sessionProcess] alive-check is lenient: the outer `su -c chroot` wrapper
     * may exit once the inner daemon detaches, so we allow up to [maxDeadReads]
     * consecutive dead-process observations before declaring failure.
     */
    fun awaitDesktopReady(timeoutMs: Long = 60_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        val minWaitDeadline = System.currentTimeMillis() + 15_000L
        val deCheck = "pgrep -x phosh >/dev/null 2>&1 || " +
            "pgrep -x phoc >/dev/null 2>&1 || " +
            "pgrep -x plasmashell >/dev/null 2>&1 || " +
            "pgrep -x kwin_wayland >/dev/null 2>&1 || " +
            "pgrep -x sway >/dev/null 2>&1 || " +
            "pgrep -x xfce4-session >/dev/null 2>&1 || " +
            "pgrep -x gnome-shell >/dev/null 2>&1 || " +
            "pgrep -x lxqt-session >/dev/null 2>&1 || " +
            "pgrep -x mate-session >/dev/null 2>&1"
        val socketCheck = "test -S /tmp/runtime-root/wayland-0 || test -S /tmp/.X11-unix/X0"

        val ready = run {
            while (System.currentTimeMillis() < deadline) {
                // 1. Full check: socket + DE process
                if (execChroot("($socketCheck) && ($deCheck)") == 0) {
                    Log.i(TAG, "Desktop readiness check passed (socket + process)")
                    Thread.sleep(500)
                    return@run true
                }

                // 2. Fallback: DE process is running
                if (execChroot(deCheck) == 0) {
                    Log.i(TAG, "Desktop process running — checking socket briefly")
                    for (i in 1..10) {
                        Thread.sleep(300)
                        if (execChroot(socketCheck) == 0) {
                            Log.i(TAG, "Desktop socket appeared")
                            return@run true
                        }
                    }
                    Log.i(TAG, "Desktop process active (stabilised)")
                    return@run true
                }

                // 3. If wrapper exited, only give up if past the grace period and no desktop process exists
                if (sessionProcess?.isAlive == false && System.currentTimeMillis() > minWaitDeadline) {
                    if (execChroot(deCheck) != 0) {
                        Log.e(TAG, "Session process terminated and no desktop process is running")
                        return@run false
                    }
                }

                try {
                    Thread.sleep(400)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return@run false
                }
            }
            Log.e(TAG, "Desktop did not become ready within ${timeoutMs}ms")
            false
        }

        if (ready) {
            SurfaceFlingerWatchdog.disarmWatchdog()
        } else {
            SurfaceFlingerWatchdog.restoreSurfaceFlinger(context, rootShell)
        }
        return ready
    }

    private fun stopTrackedSessionProcess() {
        sessionProcess?.let { process ->
            try {
                process.destroyForcibly()
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            } catch (error: Exception) {
                Log.w(TAG, "Error stopping tracked session: ${error.message}")
            }
        }
        sessionProcess = null
    }

    /** Kill only processes whose filesystem root is this app's chroot. */
    private fun killRootfsProcesses() {
        val root = rootfsDir.absolutePath
        val command = """
            targets=''
            for proc in /proc/[0-9]*; do
                [ "${'$'}proc/root" -ef '$root' ] 2>/dev/null || continue
                targets="${'$'}targets ${'$'}{proc#/proc/}"
            done
            if [ -n "${'$'}targets" ]; then
                kill -TERM ${'$'}targets 2>/dev/null || true
                sleep 1
                for pid in ${'$'}targets; do
                    [ "/proc/${'$'}pid/root" -ef '$root' ] 2>/dev/null &&
                        kill -KILL "${'$'}pid" 2>/dev/null || true
                done
            fi
        """.trimIndent()
        try {
            val result = rootShell.exec(command) { output ->
                if (output.isNotBlank()) Log.d(TAG, "cleanup: ${output.trimEnd()}")
            }
            if (result == 0) {
                Log.i(TAG, "Cleared stale chroot processes")
            } else {
                Log.w(TAG, "Stale chroot cleanup exited with code $result")
            }
        } catch (error: Exception) {
            Log.w(TAG, "Could not clear stale chroot processes", error)
        }
    }

    /** Stop the chroot session and unmount bind mounts. */
    fun stopSession() {
        Log.i(TAG, "Stopping session...")
        try {
            execChroot("${NativOSInit.SCRIPT_PATH} stop")
        } catch (e: Exception) {
            Log.w(TAG, "Error invoking nativOS-init stop: ${e.message}")
        }
        stopTrackedSessionProcess()
        killRootfsProcesses()
        if (NativOSPreferences.directDrmMode(context)) {
            Log.i(TAG, "Restoring SurfaceFlinger after Direct DRM session...")
            SurfaceFlingerWatchdog.restoreSurfaceFlinger(context, rootShell)
        }
        performanceManager.restoreOriginalGovernors()
        unmountAll()
        Log.i(TAG, "Session stopped")
    }

    /** Unmount all NativOS-related mounts. */
    fun unmountAll() {
        if (!hasRoot()) return
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

            targets.forEach { target ->
                try {
                    rootShell.exec("umount -l $target 2>/dev/null || true")
                    Log.i(TAG, "Unmounted $target")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to unmount $target: ${e.message}")
                }
            }
            if (NativOSPreferences.storageMode(context) == "image") {
                loopDeviceManager.unmountImage(rootfsDir)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in unmountAll: ${e.message}")
        }
    }

    // ── Command execution ──

    /** Execute a command inside the chroot as root. */
    fun execChroot(command: String, onLog: (String) -> Unit = {}): Int {
        // Never inherit Android's TMPDIR (normally /data/local/tmp): that path
        // does not exist inside the chroot and breaks GPG/Flatpak temporary dirs.
        val shell = if (File(rootfsDir, "usr/bin/bash").exists() || File(rootfsDir, "bin/bash").exists()) "/bin/bash" else "/bin/sh"
        val wrapped = "export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin; " +
            "export TMPDIR=/tmp HOME=/root; $command"
        val output = rootShell.exec("chroot ${rootfsDir.absolutePath} $shell -c ${shellQuote(wrapped)}") { chunk ->
            Log.d(TAG, "chroot: ${chunk.trimEnd()}")
            onLog(chunk)
        }
        return output
    }

    private fun mountIfNeeded(relative: String, mountArgs: String, alreadyMounted: () -> Boolean) {
        if (alreadyMounted()) return
        val target = File(rootfsDir, relative).absolutePath
        try {
            rootShell.exec("mkdir -p $target && mount $mountArgs $target")
            Log.i(TAG, "Mounted $target")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mount $target: ${e.message}")
        }
    }

    private fun shellQuote(input: String): String {
        return "'" + input.replace("'", "'\"'\"'") + "'"
    }
}
