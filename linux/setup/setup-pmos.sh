#!/bin/sh
# ═══════════════════════════════════════════════════════════════════
#  NativOS — Alpine Linux + postmarketOS Setup Script
#
#  Configures an Alpine rootfs with postmarketOS repositories and
#  installs the Phosh mobile desktop environment and nativOS-init.
# ═══════════════════════════════════════════════════════════════════

set -e

echo "╔═══════════════════════════════════════════════════╗"
echo "║    NativOS — Setting up Alpine + postmarketOS     ║"
echo "╚═══════════════════════════════════════════════════╝"
echo ""

export PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin

# ── 1. Configure repositories ──
echo "[1/7] Setting up Alpine & postmarketOS repositories..."
ALPINE_VERSION="${ALPINE_VERSION:-v3.21}"
PMOS_VERSION="${PMOS_VERSION:-v24.12}"

cat > /etc/apk/repositories << REPOSEOF
https://dl-cdn.alpinelinux.org/alpine/${ALPINE_VERSION}/main
https://dl-cdn.alpinelinux.org/alpine/${ALPINE_VERSION}/community
https://mirror.postmarketos.org/postmarketos/${PMOS_VERSION}
REPOSEOF

# Fetch and install postmarketos-keys so packages can be verified
apk update --allow-untrusted || apk update
apk add --no-cache --allow-untrusted postmarketos-keys 2>/dev/null || true
apk update

# ── 2. Core system, init and utilities ──
echo "[2/7] Installing core system and OpenRC..."
apk add --no-cache \
    openrc \
    dbus \
    dbus-x11 \
    polkit \
    bash \
    shadow \
    sudo \
    curl \
    wget \
    ca-certificates \
    python3 \
    py3-pip \
    tzdata \
    gcompat

# ── 3. Phosh mobile desktop stack ──
echo "[3/7] Installing Phosh desktop & mobile packages..."
apk add --no-cache \
    phoc \
    phosh \
    squeekboard \
    feedbackd \
    adwaita-icon-theme \
    fonts-cantarell \
    desktop-file-utils \
    gtk-update-icon-cache \
    glib \
    gsettings-desktop-schemas || {
    echo "[!] Fallback: installing individual desktop packages..."
    apk add --no-cache phoc phosh squeekboard || true
}

# ── 4. Mobile applications ──
echo "[4/7] Installing mobile apps..."
apk add --no-cache \
    gnome-console \
    gnome-calculator \
    gnome-clocks \
    megapixels \
    2>/dev/null || true

# ── 5. Graphics & compilation tools for hooks ──
echo "[5/7] Installing graphics tools and build dependencies..."
apk add --no-cache \
    build-base \
    libxcb-dev \
    vulkan-loader \
    mesa-vulkan-freedreno \
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
gcc -shared -fPIC -o /usr/local/lib/libsocket_hook.so /tmp/socket_hook.c || true

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

# ── 7. Configure nativOS-init and OpenRC ──
echo "[7/7] Configuring system initialization..."
mkdir -p /etc/nativOS /usr/local/sbin /run/nativOS /var/log/nativOS

# Install OpenRC service
if [ -d /etc/init.d ]; then
    rc-update add dbus default 2>/dev/null || true
fi

# Compile schemas & update icon caches
glib-compile-schemas /usr/share/glib-2.0/schemas 2>/dev/null || true
gtk-update-icon-cache -f -t /usr/share/icons/hicolor 2>/dev/null || true
gtk-update-icon-cache -f -t /usr/share/icons/Adwaita 2>/dev/null || true

echo ""
echo "✅ Alpine Linux + postmarketOS setup complete!"
echo "   Next: use 'nativOS-init start' to launch the desktop."
