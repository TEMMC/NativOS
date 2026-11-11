#!/usr/bin/env bash
# Build the bundled NativOS ARM64 rootfs from Debian's official ARM64 image.
# Installs the pinned MT-Phoc/MT-Phosh release assets and the repository's
# NativOS bridge files. Android/Kotlin application sources are not modified.
set -Eeuo pipefail

ROOTFS="${1:?usage: prepare-nativos-rootfs.sh ROOTFS_DIR OUTPUT_TGZ}"
OUTPUT="${2:?usage: prepare-nativos-rootfs.sh ROOTFS_DIR OUTPUT_TGZ}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"

PHOC_URL="https://github.com/TEMMC/MT-phoc/releases/download/MTalpha_1/MT-phoc-linux-install.tar.gz"
PHOC_SHA256="c6cdeed75f4565ad595fc945864c7a5acf83eb3bff7fa8d04f7acb5ad735188e"
PHOSH_URL="https://github.com/TEMMC/MT-phosh-/releases/download/MT_alpha_1/mt-phosh-linux-install.tar.gz"
PHOSH_SHA256="ebed6b019f923b8486b517848acada3bd1a0b3468c0439beb22f02036b314398"

ROOTFS="$(realpath "$ROOTFS")"
mkdir -p "$(dirname "$OUTPUT")"
test -x "$ROOTFS/bin/bash"
test -f "$ROOTFS/etc/debian_version"
test "$(dpkg --print-architecture)" = amd64

sudo apt-get update
sudo apt-get install -y qemu-user-static binfmt-support

# Docker Official Image is Debian's published ARM64 base rootfs. Run its
# package manager under qemu-user-static; no cross-architecture binaries are
# executed natively on the x86_64 Actions runner.
sudo install -m 0755 /usr/bin/qemu-aarch64-static "$ROOTFS/usr/bin/qemu-aarch64-static"
sudo rm -f "$ROOTFS/etc/resolv.conf"
sudo cp /etc/resolv.conf "$ROOTFS/etc/resolv.conf"

run_target() {
  sudo chroot "$ROOTFS" /usr/bin/qemu-aarch64-static /bin/bash -Eeuo pipefail -c "$1"
}

run_target '
  export DEBIAN_FRONTEND=noninteractive
  export TZ=Etc/UTC
  printf "deb http://deb.debian.org/debian trixie main contrib non-free non-free-firmware\n" > /etc/apt/sources.list
  printf "deb http://deb.debian.org/debian trixie-updates main contrib non-free non-free-firmware\n" >> /etc/apt/sources.list
  printf "deb http://security.debian.org/debian-security trixie-security main contrib non-free non-free-firmware\n" >> /etc/apt/sources.list
  apt-get update
  apt-get install -y --no-install-recommends \
    ca-certificates curl xz-utils tar gzip dbus dbus-x11 systemd \
    phosh phoc gnome-console gnome-settings-daemon gsettings-desktop-schemas \
    adwaita-icon-theme hicolor-icon-theme libglib2.0-bin \
    xwayland weston x11-utils x11-xserver-utils locales \
    python3 python3-dbus python3-gi python3-pip \
    squeekboard pipewire pipewire-pulse wireplumber feedbackd \
    geoclue-2.0 iio-sensor-proxy
  locale-gen en_US.UTF-8
'

install_mt() {
  local name="$1" url="$2" expected="$3" expected_path="$4"
  local archive
  archive="$(mktemp "/tmp/nativos-${name}.XXXXXX.tar.gz")"
  curl --fail --location --silent --show-error --retry 3 "$url" -o "$archive"
  echo "${expected}  ${archive}" | sha256sum --check --status
  tar -tzf "$archive" | sed 's#^\./##' | grep -Fxq "$expected_path"
  sudo tar --no-same-owner -xzf "$archive" -C "$ROOTFS"
  sudo test -x "$ROOTFS/$expected_path"
  rm -f "$archive"
}

install_mt phoc "$PHOC_URL" "$PHOC_SHA256" "usr/local/bin/phoc"
install_mt phosh "$PHOSH_URL" "$PHOSH_SHA256" "usr/local/bin/phosh"

# Preserve the MT builds as the selected executables at the paths checked by
# Gradle and used by NativOS' existing launch/session code.
sudo install -d "$ROOTFS/usr/bin" "$ROOTFS/etc/nativOS" "$ROOTFS/run/nativOS"
sudo ln -sfn /usr/local/bin/phoc "$ROOTFS/usr/bin/phoc"
sudo ln -sfn /usr/local/bin/phosh "$ROOTFS/usr/bin/phosh"
sudo install -m 0755 "$REPO_ROOT/linux/bridge-client/nativOS-bridge.py" "$ROOTFS/usr/local/bin/nativOS-bridge"
sudo install -m 0644 "$REPO_ROOT/linux/config/phoc.ini" "$ROOTFS/etc/nativOS/phoc.ini"
sudo install -m 0644 "$REPO_ROOT/linux/config/nativOS-bridge.service" "$ROOTFS/etc/systemd/system/nativOS-bridge.service"

# Install the bridge service without trying to start system services in chroot.
sudo mkdir -p "$ROOTFS/etc/systemd/system/multi-user.target.wants"
sudo ln -sfn ../nativOS-bridge.service "$ROOTFS/etc/systemd/system/multi-user.target.wants/nativOS-bridge.service"
sudo mkdir -p "$ROOTFS/usr/share/nativos"
sudo tee "$ROOTFS/etc/nativOS/mt-desktop-builds.txt" >/dev/null <<EOF
phoc_release=MTalpha_1
phoc_sha256=$PHOC_SHA256
phosh_release=MT_alpha_1
phosh_sha256=$PHOSH_SHA256
EOF

# Optional phone companion apps: install only where Debian Trixie publishes
# them. Their absence must not prevent the core desktop rootfs from building.
for package in gnome-calls chatty megapixels gnome-contacts gnome-clocks gnome-calculator gnome-text-editor nautilus firefox-esr; do
  run_target "export DEBIAN_FRONTEND=noninteractive; apt-get install -y --no-install-recommends $package" || echo "::warning::Optional Debian package unavailable: $package"
done

run_target '
  if command -v glib-compile-schemas >/dev/null 2>&1; then
    glib-compile-schemas /usr/share/glib-2.0/schemas
    if [ -d /usr/local/share/glib-2.0/schemas ]; then
      glib-compile-schemas /usr/local/share/glib-2.0/schemas
    fi
  fi
  ldconfig
  apt-get clean
  rm -rf /var/lib/apt/lists/*
'

# Remove the temporary cross-architecture emulator from the shipped image.
sudo rm -f "$ROOTFS/usr/bin/qemu-aarch64-static"
sudo rm -f "$ROOTFS/etc/resolv.conf"
sudo ln -s /run/systemd/resolve/stub-resolv.conf "$ROOTFS/etc/resolv.conf"

test -x "$ROOTFS/usr/bin/phoc"
test -x "$ROOTFS/usr/bin/phosh"
test -x "$ROOTFS/usr/bin/kgx"
test -f "$ROOTFS/usr/share/glib-2.0/schemas/org.gnome.settings-daemon.peripherals.gschema.xml"
sudo bash "$REPO_ROOT/scripts/build-rootfs-asset.sh" "$ROOTFS" "$OUTPUT"
