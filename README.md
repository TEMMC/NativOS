# MT-GNOME

MT-GNOME is an Android-native desktop and launcher for Android phones. It is built as an Android application; it is **not** a Linux chroot, a Wayland desktop, or a port of GNOME Shell.

## Project goal

Provide a lightweight desktop-style experience on Android with:

- A desktop workspace with user-selectable wallpapers and persistent customization.
- An app drawer for installed Android applications and discovered Linux desktop entries.
- A desktop app bar that can be shown or hidden.
- Android app windows with move, resize, minimize, maximize, and close controls where firmware permits.
- A distribution catalogue for installing and managing ARM64 Linux root filesystems.
- A Terminal dock shortcut that launches the **real GNOME Terminal package inside an installed Linux distribution**.
- A package-install action on each supported installed distribution when GNOME Terminal is missing.
- Isolated Linux application launch using a private mount namespace.

MT-GNOME does **not** embed its own terminal emulator or interactive PTY. It does not substitute Bash, BusyBox, or an Android text view for GNOME Terminal. It does not install or bundle MT-Phosh, MT-Phoc, Phosh, or Phoc.

## Build

The dedicated GitHub Actions workflow is:

`.github/workflows/mt-gnome-build.yml`

It builds the Android `:gnome` module and publishes a debug APK. A signed release artifact is produced only when the repository's release-signing secrets are configured.

To build locally with JDK 17 and Android SDK 36:

```sh
cd android
./gradlew :gnome:assembleDebug
```

The resulting debug APK is under:

```
android/gnome/build/outputs/apk/debug/
```

## Architecture

- Android application module: `:gnome`
- Application ID: `com.mt.gnome`
- Minimum Android version: Android 14 (API 34)
- Kotlin and Android SDK 36
- Embedded X11 and Wayland runtime modules, independently maintained
- Linux distro catalogue and package installer
- Real GNOME Terminal and Linux desktop applications execute inside their selected distro rootfs; no Android-hosted terminal emulator is bundled.

Linux application launch requires root approval and a compatible running X11 or Wayland display session. Installing GNOME Terminal downloads the distribution's graphical dependencies and can require substantial storage and network data. A package being installed does not prove that its GUI can render: display-socket, compositor, input, and D-Bus integration must also be available.

The distro runner uses a private mount namespace and a private devpts instance. It does not bind-mount the Android host's complete `/dev` or share the host PTY device.


## MT-GNOME advanced desktop customization

The MT-GNOME launcher includes persistent desktop preferences for built-in and custom wallpapers, 12 desktop themes, custom accent colours, independent top-panel and dock transparency, desktop icon sizing and label visibility, configurable application-grid density (3–8 columns), dock visibility and width, pointer/trackpad input, orientation, and four named desktop workspaces. Preferences are stored locally on the device. Android continues to manage app tasks and system permissions; freeform behaviour depends on firmware support and user-approved root access. Linux desktop-entry discovery does not by itself guarantee that an app window can be displayed: a compatible running X11 or Wayland session and graphics/input integration are required.
