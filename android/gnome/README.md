# MT-GNOME

MT-GNOME is a separate native Android HOME launcher in the NativOS repository. It does not replace the MT-OS/Phosh application or change its Linux runtime.

- Application ID: `com.mt.gnome`
- Target: Samsung Galaxy A04e / Android 14 / ARM64
- UI: Kotlin and native Android Views
- Root integration: optional Magisk `su` bridge for Android freeform-mode requests
- Themes: Aurora, Emerald, Midnight
- Signing: reuses the existing NativOS release keystore configuration and GitHub Actions secrets

## Graphics architecture: X11 and Wayland

The Android project now includes both pinned MagicDesk graphics engines:

- **X11:** embedded X server from `:x11-runtime`, including its native Lorie engine.
- **Wayland:** embedded per-toplevel compositor from `:wayland-runtime`.
- **Shared hosting:** both depend on `:hosted-runtime` for Android Surface presentation, shared frame transport and graphics handling.
- **Architecture:** X11 and Wayland are separate session protocols. Xwayland is not enabled by the pinned Wayland runtime; X11 applications use the X11 backend, while native Wayland applications use Wayland.

The Wayland runtime compiles its pinned ARM64/API-34 native dependencies during the Linux GitHub Actions build. The workflow installs Meson 1.9.1, CMake/Ninja, Bison/Flex and host scanner dependencies, then runs the runtime unit tests and packages the native libraries and license notices into the MT-GNOME APK.

**Important implementation boundary:** packaging both engines is the graphics-runtime foundation, not proof that the MT-GNOME interface already launches Linux GUI applications. The current MT-GNOME source still needs its Linux graphics session manager, selected-distro command executor, Wayland/X11 session lifecycle, Android Surface host windows, input/clipboard bridges and application catalog wired to these runtime APIs. Do not treat the feature as device-ready until that UI/session integration is implemented and tested on the A04e. The existing Android launcher, terminal and desktop tools remain intact.

## Build

From `NativOS/android`:

```bash
./gradlew :x11-runtime:testDebugUnitTest
./gradlew :wayland-runtime:testDebugUnitTest :wayland-runtime:lintDebug
./gradlew :gnome:assembleDebug
./gradlew :gnome:assembleRelease
```

The release build reads `signing/keystore.properties`, the same file used by the existing MT-OS module. The MT-GNOME workflow materializes it from `MTOS_KEYSTORE_BASE64`, `MTOS_KEYSTORE_PASSWORD`, `MTOS_KEY_ALIAS`, and `MTOS_KEY_PASSWORD`. No keystore or password is committed.

For the Wayland native build, install Meson 1.9.1 in `android/build/wayland-tools`:

```bash
python3 -m venv build/wayland-tools
build/wayland-tools/bin/pip install meson==1.9.1
```

## Window management

MT-GNOME is a launcher, not a replacement for Android's WindowManager. It requests freeform launch mode and enables relevant global settings through Magisk only when the user selects those controls. Samsung's Android 14 framework may ignore or restrict freeform mode. No boot, recovery, partition, SELinux, or system files are modified.

## Included desktop features

- Native Android HOME launcher with searchable installed-app drawer and bundled offline wallpapers.
- Persistent overlay dock and optional pointer/trackpad overlay (subject to Android overlay and accessibility permissions).
- Terminal activity backed by the Termux terminal emulator and native PTY bridge.
- Desktop tools hub with device/RAM/storage/uptime monitoring, battery and charging telemetry, app launching, visible-process inspection, clipboard tools, Android settings shortcuts, SAF file picking, local offline notes, and a user-triggered shareable diagnostics report.
- MT Files: a Storage Access Framework file manager for user-granted folders, with directory navigation, file opening, folder creation, rename, delete, and share actions (provider capabilities vary).
- Productivity shortcuts to installed calendar, clock, browser, downloads, and Android share targets.
- Four persistent desktop workspaces with switcher, custom names, and independent wallpaper selection. Android still owns app task placement; these are separate MT-GNOME desktop surfaces, not isolated Android task containers.
- Optional root bridge for requesting Android freeform app windows; Android/Samsung firmware retains final control over windowing.

## Platform boundaries

MT-GNOME is an Android application, not a custom Android ROM. Wi-Fi, Bluetooth, VPN, notifications, accessibility, screen capture, audio routing, external-display policy, and protected storage remain subject to Android permissions and system services. A shortcut to a system page is not equivalent to an in-app replacement for that service. Process enumeration is limited by Android. Calendar, clock, browser, and download actions depend on installed Android handlers.

The following larger capabilities are not represented as complete merely by listing them: independent multi-window workspaces, a dual-pane file manager, an in-app notification shade, a full settings framework, remote desktop, SSH/SFTP, cloud sync/accounts, extension marketplace, AI assistant, and full external-display desktop mode. They require dedicated implementations, permission flows, and device testing.
