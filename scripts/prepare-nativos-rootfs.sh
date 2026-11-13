#!/usr/bin/env bash
# Build the bundled MT-OS ARM64 rootfs from Debian's official ARM64 image.
# Installs the pinned MT-Phoc/MT-Phosh release assets and the repository's
# MT-OS bridge files. Android/Kotlin application sources are not modified.
set -Eeuo pipefail

ROOTFS="${1:?usage: prepare-mt-os-rootfs.sh ROOTFS_DIR OUTPUT_TGZ}"
OUTPUT="${2:?usage: prepare-mt-os-rootfs.sh ROOTFS_DIR OUTPUT_TGZ}"
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

# ARM64 execution is registered by docker/setup-qemu-action in the workflow.
# The kernel binfmt handler dispatches ARM64 ELF binaries to QEMU automatically.
sudo rm -f "$ROOTFS/etc/resolv.conf"
sudo cp /etc/resolv.conf "$ROOTFS/etc/resolv.conf"
# The Debian base image already ships deb822 sources; remove them because this
# script manages the classic sources.list below. This avoids duplicate indexes.
sudo rm -f "$ROOTFS/etc/apt/sources.list.d/debian.sources"

# Provide the virtual filesystems expected by Debian maintainer scripts and
# apt's terminal logger while operating inside the ARM64 chroot.
for mountpoint in proc sys dev dev/pts; do
  sudo mkdir -p "$ROOTFS/$mountpoint"
done
sudo mountpoint -q "$ROOTFS/proc" || sudo mount -t proc proc "$ROOTFS/proc"
# Mount a fresh sysfs instance instead of recursively exposing the runner host /sys.
# A recursive bind inherits dozens of host kernel pseudo-files and nested mounts.
sudo mountpoint -q "$ROOTFS/sys" || sudo mount -t sysfs sysfs "$ROOTFS/sys"
sudo mountpoint -q "$ROOTFS/dev" || sudo mount --rbind /dev "$ROOTFS/dev"
sudo mountpoint -q "$ROOTFS/dev/pts" || sudo mount --bind /dev/pts "$ROOTFS/dev/pts"

cleanup_chroot_mounts() {
  sudo umount -R "$ROOTFS/dev/pts" 2>/dev/null || true
  sudo umount -R "$ROOTFS/dev" 2>/dev/null || true
  sudo umount "$ROOTFS/sys" 2>/dev/null || true
  sudo umount -R "$ROOTFS/proc" 2>/dev/null || true
}
trap cleanup_chroot_mounts EXIT

run_target() {
  sudo chroot "$ROOTFS" /bin/bash -Eeuo pipefail -c "$1"
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
    librsvg2-common adwaita-icon-theme hicolor-icon-theme libglib2.0-bin \
    xwayland weston x11-utils x11-xserver-utils locales \
    python3 python3-dbus python3-gi python3-pip \
    squeekboard pipewire pipewire-pulse wireplumber feedbackd \
    geoclue-2.0 iio-sensor-proxy
  locale-gen en_US.UTF-8
'

install_mt() {
  local name="$1" url="$2" expected="$3" binary="$4"
  local archive unpack found
  archive="$(mktemp "/tmp/mt-os-${name}.XXXXXX.tar.gz")"
  unpack="$(mktemp -d "/tmp/mt-os-${name}.XXXXXX")"
  echo "::group::Install ${name} release asset"
  curl --fail --location --silent --show-error --retry 3 "$url" -o "$archive"
  echo "${expected}  ${archive}" | sha256sum --check
  echo "Archive contents (${name}):"
  local listing="${archive}.contents"
  tar -tzf "$archive" > "$listing"
  sed -n '1,80p' "$listing"
  tar -xzf "$archive" -C "$unpack"
  found="$(find "$unpack" -type f -name "$binary" -perm /111 -print -quit)"
  if [ -z "$found" ]; then
    echo "::error::The verified ${name} archive contains no executable named ${binary}."
    find "$unpack" -maxdepth 5 -type f -printf '%P\n' | head -120
    exit 1
  fi
  # Keep any bundled libraries, data files and support files in their
  # published paths, then expose the discovered executable at a stable path.
  sudo tar --no-same-owner -xzf "$archive" -C "$ROOTFS"
  sudo install -D -m 0755 "$found" "$ROOTFS/usr/local/bin/$binary"
  # Install a real executable at /usr/bin as well. Avoid relying on a symlink
  # through /usr/local, which can be absent/broken in merged-/usr base images.
  sudo install -D -m 0755 "$found" "$ROOTFS/usr/bin/$binary"
  sudo test -x "$ROOTFS/usr/local/bin/$binary"
  sudo test -x "$ROOTFS/usr/bin/$binary"
  rm -rf "$archive" "$listing" "$unpack"
  echo "::endgroup::"
}

install_mt phoc "$PHOC_URL" "$PHOC_SHA256" "phoc"
install_mt phosh "$PHOSH_URL" "$PHOSH_SHA256" "phosh"

# Preserve the MT builds as real executable files at the paths checked by
# Gradle and used by MT-OS' existing launch/session code.
sudo install -d "$ROOTFS/usr/bin" "$ROOTFS/etc/mt-os" "$ROOTFS/run/mt-os"
sudo install -m 0755 "$ROOTFS/usr/local/bin/phoc" "$ROOTFS/usr/bin/phoc"
sudo install -m 0755 "$ROOTFS/usr/local/bin/phosh" "$ROOTFS/usr/bin/phosh"
sudo install -m 0755 "$REPO_ROOT/linux/bridge-client/nativOS-bridge.py" "$ROOTFS/usr/local/bin/mt-os-bridge"
sudo install -m 0644 "$REPO_ROOT/linux/config/phoc.ini" "$ROOTFS/etc/mt-os/phoc.ini"
sudo install -m 0644 "$REPO_ROOT/linux/config/nativOS-bridge.service" "$ROOTFS/etc/systemd/system/mt-os-bridge.service"

# Install the bridge service without trying to start system services in chroot.
sudo mkdir -p "$ROOTFS/etc/systemd/system/multi-user.target.wants"
sudo ln -sfn ../mt-os-bridge.service "$ROOTFS/etc/systemd/system/multi-user.target.wants/mt-os-bridge.service"
sudo mkdir -p "$ROOTFS/usr/share/mt-os"
sudo tee "$ROOTFS/etc/mt-os/mt-desktop-builds.txt" >/dev/null <<EOF
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

# Reinstall the SVG loader after optional desktop-app installs and validate
# its actual multiarch location instead of assuming a hard-coded path.
run_target '
  export DEBIAN_FRONTEND=noninteractive
  apt-get install -y --reinstall --no-install-recommends librsvg2-common
  dpkg --configure -a
  dpkg-query -W librsvg2-common
  find /usr/lib -name libpixbufloader-svg.so -print -quit | grep -q .
'

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

# Unmount host-backed virtual filesystems before staging/copying the rootfs.
# Never allow live host /proc, /sys or /dev mounts into the packaged image.
cleanup_chroot_mounts
for mountpoint in proc sys dev/pts dev; do
  if sudo mountpoint -q "$ROOTFS/$mountpoint"; then
    echo "::error::Temporary host filesystem is still mounted: $ROOTFS/$mountpoint"
    exit 1
  fi
done

# Remove the temporary cross-architecture emulator from the shipped image.
sudo rm -f "$ROOTFS/etc/resolv.conf"
sudo ln -s /run/systemd/resolve/stub-resolv.conf "$ROOTFS/etc/resolv.conf"

for required in \
  "$ROOTFS/usr/bin/phoc" \
  "$ROOTFS/usr/bin/phosh" \
  "$ROOTFS/usr/bin/kgx" \
  "$ROOTFS/usr/share/glib-2.0/schemas/org.gnome.settings-daemon.peripherals.gschema.xml" \
  "$ROOTFS/usr/lib/aarch64-linux-gnu/gdk-pixbuf-2.0/2.10.0/loaders/libpixbufloader-svg.so"; do
  if [ ! -e "$required" ]; then
    echo "::error::Required rootfs component is missing: $required"
    exit 1
  fi
done
if ! sudo find "$ROOTFS/usr/lib" -name libpixbufloader-svg.so -print -quit | grep -q .; then
  echo "::error::librsvg2-common is installed but its GDK-Pixbuf SVG loader is missing."
  exit 1
fi
sudo bash "$REPO_ROOT/scripts/build-rootfs-asset.sh" "$ROOTFS" "$OUTPUT"
