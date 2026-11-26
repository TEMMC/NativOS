# MT-GNOME

MT-GNOME is an Android-native desktop and launcher for Android phones. It is built as an Android application; it is **not** a Linux chroot, a Wayland desktop, or a port of GNOME Shell.

## Project goal

Provide a lightweight desktop-style experience on Android with:

- A desktop workspace with a user-selectable wallpaper.
- An app drawer for launching installed Android applications.
- A desktop/app bar that can be shown or hidden.
- Desktop windows with move, resize, minimize, maximize, and close controls.
- A terminal window with an inline shell prompt and interactive terminal input.
- PTY-backed terminal sessions with terminal-style output, cursor handling, and ANSI colours.
- Android-native permissions and lifecycle handling.

The terminal and windowing features must remain part of the Android application. Do not install or bundle MT-Phosh, MT-Phoc, Phosh, Phoc, or a Linux desktop package stack as dependencies of MT-GNOME.

## Build

The dedicated GitHub Actions workflow is:

`.github/workflows/mt-gnome-build.yml`

It builds only the Android `:gnome` module and publishes the debug APK as the `MT-GNOME-debug` workflow artifact. A signed release artifact is produced only when the repository's release-signing secrets are configured.

To build locally with JDK 17 and Android SDK 35:

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
- Minimum Android version: Android 8.0 (API 26)
- Kotlin and Android SDK
- Terminal view dependency: Termux terminal-view library

MT-GNOME is kept separate from the legacy MT-OS Linux desktop module. The MT-GNOME build must not download, install, or require packages from the MT-Phosh or MT-Phoc projects.

## Development priorities

1. Keep the app lightweight and Android-native.
2. Make the desktop, app bar, windows, and controls genuinely interactive.
3. Keep the wallpaper visible through the transparent terminal window.
4. Render typed commands and output inside the terminal, with the prompt at the bottom and input immediately after the `$` prompt.
5. Fix PTY allocation and session lifecycle before claiming full interactive-terminal support.
6. Verify every change with the dedicated MT-GNOME GitHub Actions build before sharing an APK.


## MT-GNOME advanced desktop customization

The MT-GNOME launcher includes persistent desktop preferences for built-in and custom wallpapers, 12 desktop themes, custom accent colours, independent top-panel and dock transparency, desktop icon sizing and label visibility, configurable application-grid density (3–8 columns), dock visibility and width, pointer/trackpad input, orientation, and four named desktop workspaces. Preferences are stored locally on the device. Android continues to manage app tasks and system permissions; freeform behaviour depends on firmware support and user-approved root access. Linux desktop-entry discovery does not by itself guarantee that an app window can be displayed: a compatible running X11 or Wayland session and graphics/input integration are required.
