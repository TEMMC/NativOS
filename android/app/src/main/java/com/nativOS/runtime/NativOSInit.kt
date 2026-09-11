package com.nativOS.runtime

import android.util.Log
import java.io.File

/**
 * Embedded NativOS initialization system and process supervisor script.
 * Deployed to /usr/local/sbin/nativOS-init inside the rootfs.
 */
object NativOSInit {
    private const val TAG = "NativOS.Init"
    const val SCRIPT_PATH = "/usr/local/sbin/nativOS-init"

    val SCRIPT_CONTENT = """
#!/bin/sh
# ═══════════════════════════════════════════════════════════════════
#  NativOS Initialization System & Process Supervisor (nativOS-init)
#
#  Modular, container/chroot-friendly init system and service manager
#  for Alpine Linux (postmarketOS) and Debian 13 (Trixie).
# ═══════════════════════════════════════════════════════════════════

set -e

SCREEN_WIDTH="${'$'}{SCREEN_WIDTH:-1080}"
SCREEN_HEIGHT="${'$'}{SCREEN_HEIGHT:-2160}"
DISPLAY_SCALE="${'$'}{DISPLAY_SCALE:-2}"
GPU_MODE="${'$'}{GPU_MODE:-software}"
APP_UID="${'$'}{APP_UID:-1000}"
HOST_TMPDIR="${'$'}{HOST_TMPDIR:-/tmp}"
RUN_DIR="/run/nativOS"
LOG_DIR="/var/log/nativOS"

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

DISPLAY_TRANSFORM="${'$'}{DISPLAY_TRANSFORM:-normal}"
WLR_RENDERER_PREF="${'$'}{WLR_RENDERER_PREF:-auto}"
MESA_GLTHREAD_PREF="${'$'}{MESA_GLTHREAD_PREF:-true}"
DIRECT_DRM_PREF="${'$'}{DIRECT_DRM_PREF:-false}"

log_info() { echo -e "${'$'}{BLUE}[nativOS-init]${'$'}{NC} ${'$'}1"; }
log_ok() { echo -e "${'$'}{GREEN}[nativOS-init:OK]${'$'}{NC} ${'$'}1"; }
log_warn() { echo -e "${'$'}{YELLOW}[nativOS-init:WARN]${'$'}{NC} ${'$'}1"; }
log_err() { echo -e "${'$'}{RED}[nativOS-init:ERROR]${'$'}{NC} ${'$'}1"; }

parse_start_args() {
    while [ ${'$'}# -gt 0 ]; do
        case "${'$'}1" in
            --width) SCREEN_WIDTH="${'$'}2"; shift 2 ;;
            --height) SCREEN_HEIGHT="${'$'}2"; shift 2 ;;
            --scale) DISPLAY_SCALE="${'$'}2"; shift 2 ;;
            --gpu) GPU_MODE="${'$'}2"; shift 2 ;;
            --app-uid) APP_UID="${'$'}2"; shift 2 ;;
            --desktop) DESKTOP_ENV="${'$'}2"; shift 2 ;;
            --tmpdir) HOST_TMPDIR="${'$'}2"; shift 2 ;;
            --transform) DISPLAY_TRANSFORM="${'$'}2"; shift 2 ;;
            --wlr-renderer) WLR_RENDERER_PREF="${'$'}2"; shift 2 ;;
            --mesa-glthread) MESA_GLTHREAD_PREF="${'$'}2"; shift 2 ;;
            --direct-drm) DIRECT_DRM_PREF="${'$'}2"; shift 2 ;;
            *) shift ;;
        esac
    done
}

stage_sysinit() {
    log_info "Stage 1: Sysinit & runtime directories..."
    # OOM killer protection for supervisor
    echo -1000 > /proc/$$/oom_score_adj 2>/dev/null || true

    # Tune kernel memory parameters for high performance
    echo 100 > /proc/sys/vm/swappiness 2>/dev/null || true
    echo 50 > /proc/sys/vm/vfs_cache_pressure 2>/dev/null || true
    echo 10 > /proc/sys/vm/dirty_ratio 2>/dev/null || true
    echo 5 > /proc/sys/vm/dirty_background_ratio 2>/dev/null || true

    mkdir -p /tmp/runtime-root
    chown root:root /tmp/runtime-root 2>/dev/null || true
    chmod 0700 /tmp/runtime-root

    mkdir -p "${'$'}RUN_DIR" "${'$'}LOG_DIR" /run/dbus /run/sshd /tmp/.X11-unix /etc/nativOS
    chmod 0755 "${'$'}RUN_DIR" "${'$'}LOG_DIR" /run/dbus
    chmod 755 /dev/snd 2>/dev/null || true
    chmod 666 /dev/snd/* /dev/video* /dev/media* /dev/ion 2>/dev/null || true

    [ -e /dev/fd ] || ln -snf /proc/self/fd /dev/fd 2>/dev/null || true
    [ -e /dev/stdin ] || ln -snf /proc/self/fd/0 /dev/stdin 2>/dev/null || true
    [ -e /dev/stdout ] || ln -snf /proc/self/fd/1 /dev/stdout 2>/dev/null || true
    [ -e /dev/stderr ] || ln -snf /proc/self/fd/2 /dev/stderr 2>/dev/null || true

    rm -f /run/dbus/pid /run/dbus/system_bus_socket
    rm -f /tmp/runtime-root/wayland-* /tmp/runtime-root/wayland-*.lock 2>/dev/null || true
    rm -f "${'$'}RUN_DIR"/*.pid 2>/dev/null || true

    if [ ! -s /etc/machine-id ]; then
        if command -v dbus-uuidgen >/dev/null 2>&1; then
            dbus-uuidgen --ensure=/etc/machine-id
        else
            cat /proc/sys/kernel/random/boot_id | tr -d '-' > /etc/machine-id 2>/dev/null || true
        fi
    fi

    if command -v chpasswd >/dev/null 2>&1; then
        echo 'root:1234' | chpasswd 2>/dev/null || true
    fi
    log_ok "Stage 1 complete"
}

stage_services() {
    log_info "Stage 2: Core system services..."

    # System D-Bus Daemon
    if ! pgrep -x dbus-daemon >/dev/null 2>&1; then
        if command -v dbus-daemon >/dev/null 2>&1; then
            log_info "Starting System D-Bus daemon..."
            mkdir -p /run/dbus
            rm -f /run/dbus/system_bus_socket /run/dbus/pid
            dbus-daemon --system --fork --nopidfile
            log_ok "System D-Bus started"
        fi
    fi

    # Polkit Daemon
    POLKIT_BIN=""
    for candidate in /usr/lib/polkit-1/polkitd /usr/libexec/polkitd /usr/lib/polkit/polkitd; do
        if [ -x "${'$'}candidate" ]; then POLKIT_BIN="${'$'}candidate"; break; fi
    done
    if [ -n "${'$'}POLKIT_BIN" ] && ! pgrep -f polkitd >/dev/null 2>&1; then
        "${'$'}POLKIT_BIN" --no-debug >"${'$'}LOG_DIR/polkitd.log" 2>&1 &
        echo ${'$'}! > "${'$'}RUN_DIR/polkitd.pid"
        log_ok "Polkit started"
    fi

    # Hardware Bridge Client
    BRIDGE_BIN=""
    for candidate in /usr/local/bin/nativOS-bridge /usr/bin/nativOS-bridge; do
        if [ -x "${'$'}candidate" ]; then BRIDGE_BIN="${'$'}candidate"; break; fi
    done
    if [ -n "${'$'}BRIDGE_BIN" ] && ! pgrep -f nativOS-bridge >/dev/null 2>&1; then
        "${'$'}BRIDGE_BIN" >"${'$'}LOG_DIR/bridge-client.log" 2>&1 &
        echo ${'$'}! > "${'$'}RUN_DIR/bridge-client.pid"
        log_ok "Hardware Bridge client started"
    fi

    # SSH Daemon
    if [ -x /usr/sbin/sshd ] && ! pgrep -x sshd >/dev/null 2>&1; then
        mkdir -p /run/sshd /root/.ssh /etc/ssh/sshd_config.d
        chmod 0700 /root/.ssh
        cat > /etc/ssh/sshd_config.d/nativOS.conf << 'SSHEOF'
Port 8022
ListenAddress 0.0.0.0
PermitRootLogin prohibit-password
PasswordAuthentication no
KbdInteractiveAuthentication no
PubkeyAuthentication yes
UsePAM no
SSHEOF
        [ -f /etc/ssh/ssh_host_rsa_key ] || ssh-keygen -A 2>/dev/null || true
        /usr/sbin/sshd -E "${'$'}LOG_DIR/sshd.log" 2>/dev/null && log_ok "SSH ready on port 8022" || true
    fi

    # OpenRC support for Alpine/postmarketOS
    if command -v rc-service >/dev/null 2>&1; then
        rc-service dbus status >/dev/null 2>&1 || rc-service dbus start 2>/dev/null || true
    fi
    log_ok "Stage 2 complete"
}

stage_desktop() {
    log_info "Stage 3: Desktop Environment (Phosh/Phoc)..."

    export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
    export TMPDIR=/tmp
    export HOME=/root
    export XDG_RUNTIME_DIR=/tmp/runtime-root
    export XDG_SESSION_TYPE=x11
    export XDG_CURRENT_DESKTOP=Phosh
    export XDG_SESSION_DESKTOP=phosh
    export DESKTOP_SESSION=phosh
    export XDG_DATA_HOME=/root/.local/share
    export XDG_DATA_DIRS=/root/.local/share/flatpak/exports/share:/var/lib/flatpak/exports/share:/run/nativOS/android-apps/share:/usr/local/share:/usr/share
    export XDG_CONFIG_DIRS=/etc/xdg
    export DISPLAY=:0
    export LANG=C.UTF-8
    export LC_ALL=C.UTF-8
    export GTK_A11Y=none
    export GSK_RENDERER=gl
    export GDK_RENDERING=vulkan

    # Determine wlroots renderer (phoc compositor engine)
    if [ "${'$'}WLR_RENDERER_PREF" = "gles2" ] || [ "${'$'}WLR_RENDERER_PREF" = "pixman" ]; then
        export WLR_RENDERER="${'$'}WLR_RENDERER_PREF"
    elif [ "${'$'}GPU_MODE" = "turnip" ] || [ "${'$'}GPU_MODE" = "zink" ] || [ "${'$'}GPU_MODE" = "virgl" ]; then
        # Default to hardware-accelerated GLES2 compositor on hardware GPUs
        export WLR_RENDERER=gles2
    else
        export WLR_RENDERER=pixman
    fi
    log_info "Desktop compositor renderer: ${'$'}WLR_RENDERER (pref: ${'$'}WLR_RENDERER_PREF)"

    if [ "${'$'}GPU_MODE" = "turnip" ]; then
        log_info "Enabling Turnip/Zink acceleration"
        export NATIVOS_GPU=turnip
        unset LIBGL_ALWAYS_SOFTWARE
        unset GBM_ALWAYS_SOFTWARE
        export GALLIUM_DRIVER=zink
        export MESA_LOADER_DRIVER_OVERRIDE=zink
        export TU_DEBUG=noconform
        export ZINK_DESCRIPTORS=lazy
        export MESA_VK_WSI_DEBUG=sw
        export MESA_VK_WSI_PRESENT_MODE=mailbox
        export MESA_SHADER_CACHE_DIR=/tmp/mesa_shader_cache
        export MESA_SHADER_CACHE_MAX_SIZE=512M
        mkdir -p /tmp/mesa_shader_cache 2>/dev/null || true
        # Find the native musl-compiled ICD first, then fall back to bundled package
        TURNIP_ICD=${'$'}(for p in /usr/lib/libvulkan_freedreno.so /usr/share/vulkan/icd.d/freedreno_icd.aarch64.json; do
            [ -e "${'$'}p" ] && echo /usr/share/vulkan/icd.d/freedreno_icd.aarch64.json && break
        done)
        [ -z "${'$'}TURNIP_ICD" ] && TURNIP_ICD=${'$'}(find /opt/nativos-gpu /usr/share/vulkan/icd.d -name "*freedreno*.json" 2>/dev/null | head -n 1)
        [ -n "${'$'}TURNIP_ICD" ] && export VK_ICD_FILENAMES="${'$'}TURNIP_ICD"
        # Use GL renderer for GTK4 (Vulkan WSI not available in X11 nested)
        export GSK_RENDERER=gl
        unset GDK_RENDERING
    elif [ "${'$'}GPU_MODE" = "virgl" ]; then
        log_info "Enabling VirGL acceleration"
        export NATIVOS_GPU=virgl
        unset LIBGL_ALWAYS_SOFTWARE
        unset GBM_ALWAYS_SOFTWARE
        export GALLIUM_DRIVER=virpipe
        export MESA_GL_VERSION_OVERRIDE=4.3
        export MESA_SHADER_CACHE_DIR=/tmp/mesa_shader_cache
        mkdir -p /tmp/mesa_shader_cache 2>/dev/null || true
    elif [ "${'$'}GPU_MODE" = "zink" ]; then
        log_info "Enabling Zink Vulkan acceleration"
        export NATIVOS_GPU=zink
        unset LIBGL_ALWAYS_SOFTWARE
        unset GBM_ALWAYS_SOFTWARE
        export GALLIUM_DRIVER=zink
        export MESA_LOADER_DRIVER_OVERRIDE=zink
        export ZINK_DESCRIPTORS=lazy
        export MESA_VK_WSI_DEBUG=sw
        export MESA_VK_WSI_PRESENT_MODE=mailbox
        export MESA_SHADER_CACHE_DIR=/tmp/mesa_shader_cache
        export MESA_SHADER_CACHE_MAX_SIZE=512M
        mkdir -p /tmp/mesa_shader_cache 2>/dev/null || true
    else
        log_info "Using Mesa multi-threaded software rendering"
        export NATIVOS_GPU=software
        export WLR_RENDERER=pixman
        export LIBGL_ALWAYS_SOFTWARE=1
        export GBM_ALWAYS_SOFTWARE=1
        export GALLIUM_DRIVER=llvmpipe
        export MESA_LOADER_DRIVER_OVERRIDE=swrast
        export LP_NUM_THREADS=${'$'}(nproc 2>/dev/null || echo 4)
    fi

    if [ "${'$'}{MESA_GLTHREAD_PREF:-true}" = "true" ]; then
        export mesa_glthread=true
        export GALLIUM_THREAD=1
    fi

    # Hardware video acceleration (Snapdragon VPU / V4L2 M2M)
    export LIBVA_DRIVER_NAME=v4l2
    export GST_VAAPI_ALL_DRIVERS=1

    if [ "${'$'}DIRECT_DRM_PREF" = "true" ]; then
        log_info "Activating Native Direct DRM/KMS mode on /dev/dri/card0"
        export WLR_BACKENDS=drm
        export WLR_DRM_DEVICES=/dev/dri/card0
        export WLR_DRM_NO_ATOMIC=0
    else
        export WLR_BACKENDS=x11
        export WLR_X11_OUTPUTS=1
        export WLR_DRM_NO_ATOMIC=1
        export WLR_DRM_DEVICES=""
    fi
    export TMPDIR="${'$'}HOST_TMPDIR"

    # SDL and Qt apps use X11 since phoc runs as nested X11 compositor.
    # Do NOT set GDK_BACKEND=x11 globally — phosh needs GDK_BACKEND=wayland
    # to connect to the phoc Wayland compositor via its -E session script.
    export SDL_VIDEODRIVER=x11
    export QT_QPA_PLATFORM=xcb

    # HiDPI cursor scaling — DISPLAY_SCALE may be a float (e.g. 1.5) so we
    # cannot use $(()) which only handles integers in POSIX sh.
    _cursor_sz=${'$'}(awk "BEGIN{printf \"%d\", int(24 * ${'$'}DISPLAY_SCALE)}" 2>/dev/null || echo 24) || true
    if [ -z "${'$'}_cursor_sz" ] || [ "${'$'}_cursor_sz" -lt 8 ] 2>/dev/null; then _cursor_sz=24; fi
    export XCURSOR_SIZE=${'$'}{_cursor_sz:-24}
    export XCURSOR_THEME=default

    PRELOAD=""
    for lib in /usr/local/lib/libsocket_hook.so /usr/local/lib/libnativos-close-range.so /usr/local/lib/libnodri3.so /usr/local/lib/libandroid-shmem.so; do
        if [ -f "${'$'}lib" ] && env -i LD_PRELOAD="${'$'}lib" /bin/sh -c 'exit 0' 2>/dev/null; then
            PRELOAD="${'$'}{PRELOAD:+${'$'}PRELOAD:}${'$'}lib"
        elif [ -f "${'$'}lib" ]; then
            log_warn "Excluding broken/incompatible preload library: ${'$'}lib"
        fi
    done

    APP_PRELOAD=""
    for lib in /usr/local/lib/libsocket_hook.so /usr/local/lib/libnativos-close-range.so /usr/local/lib/libandroid-shmem.so; do
        if [ -f "${'$'}lib" ] && env -i LD_PRELOAD="${'$'}lib" /bin/sh -c 'exit 0' 2>/dev/null; then
            APP_PRELOAD="${'$'}{APP_PRELOAD:+${'$'}APP_PRELOAD:}${'$'}lib"
        fi
    done

    export LD_PRELOAD="${'$'}PRELOAD"
    export NATIVOS_APP_LD_PRELOAD="${'$'}APP_PRELOAD"

    # Performance tuning
    export MESA_VK_WSI_PRESENT_MODE=mailbox
    export TU_DEBUG=noconform
    export vblank_mode=0
    export WLR_NO_HARDWARE_CURSORS=1
    export LP_NUM_THREADS=${'$'}(nproc 2>/dev/null || echo 4)
    # Request performance CPU governor if possible
    for gov in /sys/devices/system/cpu/cpu*/cpufreq/scaling_governor; do
        echo performance > "${'$'}gov" 2>/dev/null || true
    done

    cat > /etc/nativOS/phoc.ini << PHOCEOF
[core]
xwayland=false

[output:X11-1]
mode=${'$'}{SCREEN_WIDTH}x${'$'}{SCREEN_HEIGHT}
scale=${'$'}DISPLAY_SCALE
transform=${'$'}{DISPLAY_TRANSFORM:-normal}
PHOCEOF

    log_ok "Display: ${'$'}{SCREEN_WIDTH}x${'$'}{SCREEN_HEIGHT} @ scale ${'$'}{DISPLAY_SCALE} (transform: ${'$'}{DISPLAY_TRANSFORM:-normal})"

    DESKTOP_ENV="${'$'}{DESKTOP_ENV:-phosh}"
    log_info "Desktop Environment: ${'$'}DESKTOP_ENV"

    case "${'$'}DESKTOP_ENV" in
        plasma-mobile)
            if command -v kwin_wayland >/dev/null 2>&1; then
                LAUNCH_CMD="kwin_wayland --x11-display :0 --width ${'$'}SCREEN_WIDTH --height ${'$'}SCREEN_HEIGHT --exit-with-session=startplasma-mobile"
            elif command -v startplasma-mobile >/dev/null 2>&1; then
                LAUNCH_CMD="startplasma-mobile"
            else
                DESKTOP_ENV="phosh"
            fi
            ;;
        sxmo)
            if command -v sxmo_hook_start.sh >/dev/null 2>&1; then
                LAUNCH_CMD="sxmo_hook_start.sh"
            elif command -v sway >/dev/null 2>&1; then
                LAUNCH_CMD="sway -c /etc/sxmo/sway/config 2>/dev/null || sway"
            else
                DESKTOP_ENV="phosh"
            fi
            ;;
        xfce4)
            if command -v startxfce4 >/dev/null 2>&1; then
                LAUNCH_CMD="startxfce4"
            elif command -v xfce4-session >/dev/null 2>&1; then
                LAUNCH_CMD="xfce4-session"
            else
                DESKTOP_ENV="phosh"
            fi
            ;;
        gnome) LAUNCH_CMD="gnome-session" ;;
        plasma)
            if command -v startplasma-x11 >/dev/null 2>&1; then
                LAUNCH_CMD="startplasma-x11"
            elif command -v kwin_wayland >/dev/null 2>&1; then
                LAUNCH_CMD="kwin_wayland --x11-display :0 --width ${'$'}SCREEN_WIDTH --height ${'$'}SCREEN_HEIGHT --exit-with-session=startplasma-wayland"
            else
                DESKTOP_ENV="phosh"
            fi
            ;;
        lxqt) LAUNCH_CMD="startlxqt" ;;
        mate) LAUNCH_CMD="mate-session" ;;
    esac

    if [ "${'$'}DESKTOP_ENV" = "phosh" ]; then
        PHOSH_EXEC="/usr/libexec/phosh"
        [ -x "${'$'}PHOSH_EXEC" ] || PHOSH_EXEC="/usr/bin/phosh"

        XDG_PORTAL_GTK=${'$'}(command -v xdg-desktop-portal-gtk 2>/dev/null || echo "/usr/libexec/xdg-desktop-portal-gtk")
        XDG_PORTAL=${'$'}(command -v xdg-desktop-portal 2>/dev/null || echo "/usr/libexec/xdg-desktop-portal")

        if ! command -v phoc >/dev/null 2>&1; then
            log_err "phoc not found! Falling back to terminal..."
            if command -v kgx >/dev/null 2>&1; then exec kgx; else sleep 3600; exit 1; fi
        fi

        # Write a wrapper script to avoid escape-hell inside -E that caused
        # "bad variable name" and phosh failing to get WAYLAND_DISPLAY.
        PHOSH_WRAPPER="/tmp/nativOS-phosh-session.sh"
        cat > "${'$'}PHOSH_WRAPPER" << 'WRAPPER_EOF'
#!/bin/sh
export WAYLAND_DISPLAY="${'$'}{WAYLAND_DISPLAY:-wayland-0}"
export XDG_RUNTIME_DIR="${'$'}{XDG_RUNTIME_DIR:-/tmp/runtime-root}"
export GDK_BACKEND=wayland
WRAPPER_EOF
        # Append runtime-expanded values (variables known at script-write time)
        cat >> "${'$'}PHOSH_WRAPPER" << WRAPPER_EOF2
export DBUS_SESSION_BUS_ADDRESS="${'$'}DBUS_SESSION_BUS_ADDRESS"
export LD_PRELOAD="${'$'}NATIVOS_APP_LD_PRELOAD"
[ -x "${'$'}XDG_PORTAL_GTK" ] && "${'$'}XDG_PORTAL_GTK" > "${'$'}LOG_DIR/portal-gtk.log" 2>&1 &
[ -x "${'$'}XDG_PORTAL" ] && "${'$'}XDG_PORTAL" > "${'$'}LOG_DIR/portal.log" 2>&1 &
exec "${'$'}PHOSH_EXEC" -U
WRAPPER_EOF2
        chmod +x "${'$'}PHOSH_WRAPPER"

        LAUNCH_CMD="phoc -C /etc/nativOS/phoc.ini -E ${'$'}PHOSH_WRAPPER"
    fi

    echo ${'$'}${'$'} > "${'$'}RUN_DIR/desktop.pid"
    FAIL_COUNT=0

    while true; do
        START_TIME=${'$'}(date +%s)
        eval dbus-run-session -- ${'$'}LAUNCH_CMD || true

        EXIT_TIME=${'$'}(date +%s)
        DURATION=${'$'}((EXIT_TIME - START_TIME))

        if [ "${'$'}DURATION" -lt 3 ]; then
            FAIL_COUNT=${'$'}((FAIL_COUNT + 1))
            # Auto-fallback watchdog: if compositor crashes under GLES2, fall back to Pixman CPU rasterizer
            if [ "${'$'}WLR_RENDERER" = "gles2" ] && [ "${'$'}WLR_RENDERER_PREF" != "gles2" ]; then
                log_warn "GLES2 compositor failed or crashed; falling back to CPU pixman renderer"
                export WLR_RENDERER=pixman
            fi
            if [ "${'$'}FAIL_COUNT" -gt 5 ]; then
                log_err "Crash loop detected. Cooling down for 5s..."
                sleep 5
                FAIL_COUNT=0
            else
                sleep 1
            fi
        else
            FAIL_COUNT=0
            sleep 0.5
        fi
        log_info "Restarting desktop session..."
    done
}

stage_stop() {
    log_info "Stopping NativOS session..."
    if [ -f "${'$'}RUN_DIR/desktop.pid" ]; then
        PID=${'$'}(cat "${'$'}RUN_DIR/desktop.pid" 2>/dev/null || true)
        [ -n "${'$'}PID" ] && kill -TERM "${'$'}PID" 2>/dev/null || true
        rm -f "${'$'}RUN_DIR/desktop.pid"
    fi

    pkill -x phosh 2>/dev/null || true
    pkill -x phoc 2>/dev/null || true
    pkill -x squeekboard 2>/dev/null || true
    pkill -x plasmashell 2>/dev/null || true
    pkill -x kwin_wayland 2>/dev/null || true
    pkill -x sway 2>/dev/null || true
    pkill -x xfce4-session 2>/dev/null || true
    pkill -x xfwm4 2>/dev/null || true
    pkill -x gnome-shell 2>/dev/null || true
    pkill -x lxqt-session 2>/dev/null || true
    pkill -x mate-session 2>/dev/null || true

    for s in bridge-client polkitd; do
        if [ -f "${'$'}RUN_DIR/${'$'}s.pid" ]; then
            PID=${'$'}(cat "${'$'}RUN_DIR/${'$'}s.pid" 2>/dev/null || true)
            [ -n "${'$'}PID" ] && kill -TERM "${'$'}PID" 2>/dev/null || true
            rm -f "${'$'}RUN_DIR/${'$'}s.pid"
        fi
    done

    pkill -f nativOS-bridge 2>/dev/null || true
    pkill -x sshd 2>/dev/null || true
    pkill -x dbus-daemon 2>/dev/null || true
    rm -rf /tmp/runtime-root/wayland-* /run/dbus/* "${'$'}RUN_DIR"/*.pid 2>/dev/null || true
    log_ok "All services stopped"
}

status() {
    echo "═══════════════════════════════════════════"
    echo "       NativOS Service Status Report       "
    echo "═══════════════════════════════════════════"
    for item in "System D-Bus:dbus-daemon --system" "Polkit:polkitd" "Bridge:nativOS-bridge" "SSH:sshd" "Phoc:phoc" "Phosh:phosh" "OSK:squeekboard"; do
        name="${'$'}{item%%:*}"
        pat="${'$'}{item##*:}"
        if pgrep -f "${'$'}pat" >/dev/null 2>&1; then
            echo -e "  ${'$'}name: ${'$'}{GREEN}RUNNING${'$'}{NC}"
        else
            echo -e "  ${'$'}name: ${'$'}{RED}STOPPED${'$'}{NC}"
        fi
    done
    [ -S /tmp/runtime-root/wayland-0 ] && echo "  Wayland Socket: READY" || echo "  Wayland Socket: NOT READY"
    [ -S /run/nativOS/bridge.sock ] && echo "  Android Bridge: CONNECTED" || echo "  Android Bridge: NOT CONNECTED"
    echo "═══════════════════════════════════════════"
}

ACTION="${'$'}{1:-status}"
shift || true

case "${'$'}ACTION" in
    start) parse_start_args "${'$'}@"; stage_sysinit; stage_services; stage_desktop ;;
    stop) stage_stop ;;
    restart) stage_stop; sleep 1; parse_start_args "${'$'}@"; stage_sysinit; stage_services; stage_desktop ;;
    status) status ;;
    sysinit) stage_sysinit ;;
    services) stage_services ;;
    *) echo "Usage: ${'$'}0 {start [OPTIONS]|stop|restart [OPTIONS]|status|sysinit|services}"; exit 1 ;;
esac
""".trimIndent()

    fun installTo(rootfsDir: File) {
        try {
            val initFile = File(rootfsDir, SCRIPT_PATH.removePrefix("/"))
            initFile.parentFile?.mkdirs()
            initFile.writeText(SCRIPT_CONTENT)
            initFile.setExecutable(true, false)

            val openrcDir = File(rootfsDir, "etc/init.d")
            if (openrcDir.exists()) {
                val openrcInit = File(openrcDir, "nativOS")
                openrcInit.writeText(SCRIPT_CONTENT)
                openrcInit.setExecutable(true, false)
            }
            Log.i(TAG, "nativOS-init script installed to ${'$'}{initFile.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "Could not install nativOS-init: ${'$'}{e.message}")
        }
    }
}
