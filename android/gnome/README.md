# MT-GNOME

MT-GNOME is a standalone native Android HOME launcher in the NativOS repository. It provides a PC-style desktop shell on Android; it is not a custom ROM or a complete Linux desktop session.

- Application ID: `com.mt.gnome`
- Target device: Samsung Galaxy A04e / Android 14 / ARM64
- UI: Kotlin and native Android Views
- Optional root integration: Magisk `su` for isolated Linux launch and Android freeform settings
- Signing: reuses the existing NativOS release keystore configuration and GitHub Actions secrets

## Desktop shell

The default desktop is intentionally clean, like an XFCE workstation: **installed apps do not automatically appear as desktop icons**. Open the Applications menu to search and browse installed apps by category. Each app has an explicit add-to-desktop control; the desktop shows only shortcuts selected by the user.

Android applications are classified into Internet, Multimedia, Office, Development, System, Utilities, Games and Other. Linux desktop entries are read from each distro's `.desktop` files, including their `Categories=` metadata. Kali-style security categories are recognised where supplied by the distro metadata, including Information Gathering, Vulnerability Analysis, Web Application Analysis, Database Assessment, Password Attacks, Wireless Attacks, Reverse Engineering, Exploitation Tools, Sniffing & Spoofing, Post Exploitation, Forensics, Reporting, Social Engineering and System Services. Unknown or incomplete metadata is placed in an Other category.

Android and Linux app visibility are separate settings and both default to enabled. Hiding a category or app type does not uninstall or disable its packages.

## Wallpaper selection

Five offline built-in nature wallpapers are packaged in `src/main/assets/wallpapers`: Mountain Lake, Forest, Mountain Peaks, Tropical Beach and Open Landscape. The wallpaper picker renders a large image preview for each choice before applying it. Users can also select a wallpaper through Android's Storage Access Framework. The GitHub Actions build downloads and validates the bundled JPEG files before packaging.

## Root and BusyBox prerequisite

For the full root-assisted Linux distribution workflow, install and enable a **BusyBox for Android / Magisk BusyBox module** in Magisk, then reboot. The BusyBox executable must be available to the root shell or a standard Magisk module path. It supplies common Unix command-line utilities used by distro-management and diagnostic shell scripts.

Verify from a root shell:

```sh
su -c 'B=$(command -v busybox); [ -n "$B" ] && "$B" | head -n 1'
```

The app also provides Settings → System & Security → **Magisk BusyBox check**. The basic Android desktop can open without BusyBox, but root-assisted distro operations may be incomplete if it is unavailable.

BusyBox is a utility collection, not a security boundary. It does not grant root permission or make shell commands inherently safe. Linux application launch continues to use a private mount namespace and a separate `devpts` instance; do not bind the Android host's complete `/dev` or share its PTY.

## Graphics architecture: X11 and Wayland

The Android project packages both pinned MagicDesk graphics engines:

- **X11:** embedded X server from `:x11-runtime`, including its native Lorie engine.
- **Wayland:** embedded per-toplevel compositor from `:wayland-runtime`.
- **Shared hosting:** both depend on `:hosted-runtime` for Android Surface presentation, shared frame transport and graphics handling.
- X11 and Wayland are separate session protocols. Xwayland is not enabled by the pinned Wayland runtime; X11 applications use the X11 backend, while native Wayland applications use Wayland.

Packaging these engines is a graphics-runtime foundation, not proof that Linux GUI applications already render correctly as Android desktop windows. The Linux GUI path still requires session lifecycle management, selected-distro command execution, display socket integration, Android Surface hosting, input and clipboard bridges, and device validation. A discovered `.desktop` file or installed GUI package does not prove that its window can be displayed on the A04e.

## Linux distributions and GNOME Terminal

The Terminal dock shortcut opens the distribution catalogue. MT-GNOME checks installed supported distributions for the real `gnome-terminal` package; if it is missing, the catalogue provides an install action for the selected distro. MT-GNOME does not embed a terminal emulator, Termux terminal view, or interactive host PTY.

Linux applications run inside the selected distro rootfs using a private mount namespace and private `devpts`. The launcher does not bind-mount the Android host's complete `/dev` or share the host PTY. A visible GNOME Terminal or Linux app window still depends on a functioning X11 or Wayland display session and working display/input integration.

MT-GNOME does not install or start MT-Phosh, MT-Phoc, Phosh or Phoc.

## Build

From `NativOS/android`:

```bash
./gradlew :x11-runtime:testDebugUnitTest
./gradlew :wayland-runtime:testDebugUnitTest :wayland-runtime:lintDebug
./gradlew :gnome:assembleDebug
./gradlew :gnome:assembleRelease
```

The release build reads `signing/keystore.properties`, the same file used by the existing MT-OS module. The MT-GNOME workflow materializes it from `MTOS_KEYSTORE_BASE64`, `MTOS_KEYSTORE_PASSWORD`, `MTOS_KEY_ALIAS` and `MTOS_KEY_PASSWORD`. No keystore or password is committed.

The Wayland native build uses Meson 1.9.1 and the Android NDK configured by the GitHub Actions workflow.

## Platform boundaries

MT-GNOME is an Android application, not a replacement for Android's WindowManager. Android controls app permissions, task management, notification policy, protected storage, audio routing and actual window placement. Samsung firmware may ignore or restrict freeform mode. A launcher shortcut to a system page is not an in-app replacement for that system service.

Linux GUI rendering, independent multi-window workspaces, compositor lifecycle, clipboard/input integration and external-display desktop mode must be validated on the target device before being described as complete.
