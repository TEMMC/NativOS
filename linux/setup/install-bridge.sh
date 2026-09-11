#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════
#  NativOS — Install Bridge Client in Chroot
#
#  Installs the Python bridge daemon and systemd service
#  that connects Linux apps to Android hardware APIs.
# ═══════════════════════════════════════════════════════════════

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
BRIDGE_DIR="$SCRIPT_DIR/../bridge-client"
CONFIG_DIR="$SCRIPT_DIR/../config"

echo "╔══════════════════════════════════════════╗"
echo "║    NativOS — Installing Bridge Client    ║"
echo "╚══════════════════════════════════════════╝"
echo ""

# ── Install Python dependencies ──
echo "[1/4] Installing Python 3..."
if command -v apk >/dev/null 2>&1; then
    apk add --no-cache python3 py3-pip py3-dbus 2>/dev/null || apk add --no-cache python3 py3-pip || true
elif command -v apt-get >/dev/null 2>&1; then
    export DEBIAN_FRONTEND=noninteractive
    apt-get install -y --no-install-recommends python3 python3-pip python3-dbus 2>/dev/null || true
fi

# ── Copy bridge daemon ──
echo "[2/4] Installing bridge daemon..."
mkdir -p /usr/local/bin
cp "$BRIDGE_DIR/nativOS-bridge.py" /usr/local/bin/nativOS-bridge
chmod +x /usr/local/bin/nativOS-bridge

# ── Copy phoc config ──
echo "[3/4] Installing phoc configuration..."
mkdir -p /etc/nativOS
if [ -f "$CONFIG_DIR/phoc.ini" ]; then
    cp "$CONFIG_DIR/phoc.ini" /etc/nativOS/phoc.ini
fi

# ── Install service (OpenRC / systemd / nativOS-init) ──
echo "[4/4] Installing service definitions..."
INIT_DIR="$SCRIPT_DIR/../init"
if [ -f "$INIT_DIR/nativOS-init" ]; then
    mkdir -p /usr/local/sbin
    cp "$INIT_DIR/nativOS-init" /usr/local/sbin/nativOS-init
    chmod +x /usr/local/sbin/nativOS-init
fi

if [ -d /etc/init.d ] && [ -f "$INIT_DIR/openrc/nativOS-bridge.initd" ]; then
    cp "$INIT_DIR/openrc/nativOS-bridge.initd" /etc/init.d/nativOS-bridge
    chmod +x /etc/init.d/nativOS-bridge
    rc-update add nativOS-bridge default 2>/dev/null || true
fi

if [ -d /etc/systemd/system ] && [ -f "$CONFIG_DIR/nativOS-bridge.service" ]; then
    cp "$CONFIG_DIR/nativOS-bridge.service" /etc/systemd/system/
    systemctl daemon-reload 2>/dev/null || true
    systemctl enable nativOS-bridge.service 2>/dev/null || true
fi

# ── Create bridge socket directory ──
mkdir -p /run/nativOS /var/log/nativOS

echo ""
echo "✅ Bridge client installed!"
echo "   Daemon: /usr/local/bin/nativOS-bridge"
echo "   Init: /usr/local/sbin/nativOS-init"
echo "   Socket: /run/nativOS/bridge.sock (bind-mounted from Android)"
