#!/bin/bash
# ═══════════════════════════════════════════════════════════════════
#  NativOS — Debian 13 (Trixie) Setup Script
#
#  Configures a Debian 13 rootfs with official Trixie repositories
#  and installs the Phosh mobile desktop environment and nativOS-init.
# ═══════════════════════════════════════════════════════════════════

set -e

echo "╔═══════════════════════════════════════════════════╗"
echo "║          NativOS — Setting up Debian 13           ║"
echo "╚═══════════════════════════════════════════════════╝"
echo ""

export DEBIAN_FRONTEND=noninteractive
export TZ=Etc/UTC
export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# ── 1. Configure Debian 13 Trixie repositories ──
echo "[1/7] Configuring Debian 13 (Trixie) repositories..."
cat > /etc/apt/sources.list << 'EOF'
deb http://deb.debian.org/debian trixie main contrib non-free non-free-firmware
deb http://deb.debian.org/debian-security trixie-security main contrib non-free non-free-firmware
deb http://deb.debian.org/debian trixie-updates main contrib non-free non-free-firmware
EOF

# Prevent root sandbox error in chroots
mkdir -p /etc/apt/apt.conf.d
echo 'APT::Sandbox::User "root";' > /etc/apt/apt.conf.d/99-disable-sandbox

dpkg --configure -a 2>/dev/null || true
apt-get update -y -q

# ── 2. Core system and D-Bus ──
echo "[2/7] Installing core system and D-Bus..."
apt-get install -y --no-install-recommends \
    dbus \
    dbus-x11 \
    policykit-1 \
    packagekit \
    sudo \
    ca-certificates \
    locales \
    wget \
    curl \
    python3 \
    python3-pip

# ── 3. Phosh mobile desktop stack ──
echo "[3/7] Installing Phosh desktop from Debian Trixie main..."
apt-get install -y --no-install-recommends \
    phoc \
    phosh \
    squeekboard \
    phosh-mobile-settings \
    feedbackd \
    gnome-settings-daemon \
    gnome-settings-daemon-common \
    librsvg2-common \
    adwaita-icon-theme \
    fonts-cantarell

# ── 4. Mobile applications ──
echo "[4/7] Installing mobile applications..."
apt-get install -y --no-install-recommends \
    gnome-console \
    gnome-calculator \
    gnome-clocks \
    megapixels \
    firefox-esr \
    2>/dev/null || true

# ── 5. Build tools for compatibility hooks ──
echo "[5/7] Installing build dependencies..."
apt-get install -y --no-install-recommends \
    gcc \
    libc6-dev \
    libxcb1-dev \
    libxcb-dri3-dev \
    libvulkan1 \
    vulkan-tools \
    2>/dev/null || true

# ── 6. Build universal compatibility hooks ──
echo "[6/7] Compiling universal compatibility hooks..."
mkdir -p /usr/local/lib /usr/local/bin /tmp

# Socket Hook
cat > /tmp/socket_hook.c << 'EOF'
#define _GNU_SOURCE
#include <sys/socket.h>
#include <sys/un.h>
#include <dlfcn.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <stddef.h>

int connect(int sockfd, const struct sockaddr *addr, socklen_t addrlen) {
    int (*real_connect)(int, const struct sockaddr *, socklen_t) = dlsym(RTLD_NEXT, "connect");
    if (addr && addr->sa_family == AF_UNIX) {
        struct sockaddr_un *un = (struct sockaddr_un *)addr;
        if (strstr(un->sun_path, ".X11-unix/X")) {
            struct sockaddr_un abstract_addr;
            memset(&abstract_addr, 0, sizeof(abstract_addr));
            abstract_addr.sun_family = AF_UNIX;
            const char *tmpdir = getenv("TMPDIR");
            if (!tmpdir) tmpdir = "/tmp";
            snprintf(abstract_addr.sun_path + 1, sizeof(abstract_addr.sun_path) - 1, "%s/.X11-unix/X0", tmpdir);
            socklen_t abs_len = offsetof(struct sockaddr_un, sun_path) + 1 + strlen(abstract_addr.sun_path + 1);
            return real_connect(sockfd, (struct sockaddr *)&abstract_addr, abs_len);
        }
    }
    return real_connect(sockfd, addr, addrlen);
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libsocket_hook.so /tmp/socket_hook.c -ldl || true

# DRI3 Hook
cat > /tmp/nodri3.c << 'EOF'
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
EOF
gcc -shared -fPIC -o /usr/local/lib/libnodri3.so /tmp/nodri3.c -ldl 2>/dev/null || gcc -shared -fPIC -o /usr/local/lib/libnodri3.so /tmp/nodri3.c -ldl -lxcb 2>/dev/null || true

# Close Range Compat
cat > /tmp/close_range_compat.c << 'EOF'
#define _GNU_SOURCE
#include <errno.h>
int close_range(unsigned int first, unsigned int last, int flags) {
    (void) first; (void) last; (void) flags;
    errno = ENOSYS;
    return -1;
}
EOF
gcc -shared -fPIC -o /usr/local/lib/libnativos-close-range.so /tmp/close_range_compat.c || true

rm -f /tmp/socket_hook.c /tmp/nodri3.c /tmp/close_range_compat.c

# ── 7. Compile schemas and caches ──
echo "[7/7] Compiling schemas and caches..."
glib-compile-schemas /usr/share/glib-2.0/schemas 2>/dev/null || true
gtk-update-icon-cache -f -t /usr/share/icons/hicolor 2>/dev/null || true
gtk-update-icon-cache -f -t /usr/share/icons/Adwaita 2>/dev/null || true

# Cleanup
apt-get clean
rm -rf /var/lib/apt/lists/*

echo ""
echo "✅ Debian 13 (Trixie) setup complete!"
echo "   Next: use 'nativOS-init start' to launch the desktop."
