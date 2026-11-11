#!/usr/bin/env bash
# NativOS — install the published MT Phoc and Phosh builds in the chroot.
# This replaces only the compositor and shell binaries/data. It does not
# change NativOS Android code, rootfs lifecycle, GPU/display config, or bridge.

set -euo pipefail

export DEBIAN_FRONTEND=noninteractive
export TZ=Etc/UTC

PHOC_URL="https://github.com/TEMMC/MT-phoc/releases/download/MTalpha_1/MT-phoc-linux-install.tar.gz"
PHOC_SHA256="c6cdeed75f4565ad595fc945864c7a5acf83eb3bff7fa8d04f7acb5ad735188e"
PHOSH_URL="https://github.com/TEMMC/MT-phosh-/releases/download/MT_alpha_1/mt-phosh-linux-install.tar.gz"
PHOSH_SHA256="ebed6b019f923b8486b517848acada3bd1a0b3468c0439beb22f02036b314398"

echo "NativOS — installing MT-Phoc and MT-Phosh"

apt-get update -y -q
apt-get install -y --no-install-recommends \
    ca-certificates curl xz-utils tar \
    xwayland weston x11-utils x11-xserver-utils dbus-x11 locales

install_mt_component() {
    local name="$1" url="$2" expected_sha="$3" expected_binary="$4"
    local archive
    archive="$(mktemp "/tmp/nativos-${name}.XXXXXX.tar.gz")"
    trap 'rm -f "$archive"' RETURN

    echo "[MT] Downloading ${name}..."
    curl --fail --location --silent --show-error --retry 3 \
        --connect-timeout 30 "$url" -o "$archive"

    echo "${expected_sha}  ${archive}" | sha256sum --check --status || {
        echo "[ERROR] ${name} archive checksum mismatch" >&2
        return 1
    }

    # Validate the expected executable is present before extracting anything.
    tar -tzf "$archive" | sed 's#^\./##' | grep -Fxq "$expected_binary" || {
        echo "[ERROR] ${name} archive does not contain ${expected_binary}" >&2
        return 1
    }

    tar --no-same-owner -xzf "$archive" -C /
    test -x "/${expected_binary}" || {
        echo "[ERROR] ${name} executable missing after extraction" >&2
        return 1
    }
    echo "[MT] ${name} installed: /${expected_binary}"
    rm -f "$archive"
    trap - RETURN
}

# Install exact release assets, not Ubuntu's potentially older distro packages.
install_mt_component "phoc" "$PHOC_URL" "$PHOC_SHA256" "usr/local/bin/phoc"
install_mt_component "phosh" "$PHOSH_URL" "$PHOSH_SHA256" "usr/local/bin/phosh"

# Keep the existing NativOS companion applications and services.
apt-get install -y --no-install-recommends \
    squeekboard \
    gnome-calls chatty megapixels gnome-contacts gnome-clocks \
    gnome-calculator gnome-text-editor nautilus gnome-terminal firefox-esr \
    pipewire pipewire-pulse wireplumber feedbackd geoclue-2.0 iio-sensor-proxy \
    || echo "[WARN] Some optional phone, audio, feedback, location, or sensor packages were unavailable."

# Compile schemas supplied by the MT builds, without touching NativOS settings.
if command -v glib-compile-schemas >/dev/null 2>&1; then
    glib-compile-schemas /usr/local/share/glib-2.0/schemas 2>/dev/null || true
    glib-compile-schemas /usr/share/glib-2.0/schemas 2>/dev/null || true
fi
ldconfig 2>/dev/null || true

mkdir -p /etc/nativOS
printf '%s\n' \
    'phoc_release=MTalpha_1' \
    'phoc_sha256=c6cdeed75f4565ad595fc945864c7a5acf83eb3bff7fa8d04f7acb5ad735188e' \
    'phosh_release=MT_alpha_1' \
    'phosh_sha256=ebed6b019f923b8486b517848acada3bd1a0b3468c0439beb22f02036b314398' \
    > /etc/nativOS/mt-desktop-builds.txt

echo "MT-Phoc and MT-Phosh installation completed."
echo "NativOS bridge installation remains separate and unchanged."
