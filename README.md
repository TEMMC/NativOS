# MT-GNOME

MT-GNOME is an Android-native, PC-style desktop launcher for Android phones. It runs as an Android application; it is not a replacement Android ROM or a complete Linux distribution.

## Desktop experience

- A clean XFCE-inspired desktop workspace. **No application shortcuts are placed on the desktop by default.**
- Open the Applications menu to browse installed apps by category, search for an app, launch it, or explicitly add its shortcut to the desktop.
- Linux desktop entries use their `.desktop` Categories metadata where available. Kali-style security categories are recognised, including Information Gathering, Vulnerability Analysis, Web Application Analysis, Database Assessment, Password Attacks, Wireless Attacks, Reverse Engineering, Exploitation Tools, Sniffing & Spoofing, Post Exploitation, Forensics, Reporting, Social Engineering and System Services.
- Android apps are grouped into practical desktop categories such as Internet, Multimedia, Office, Development, System, Utilities and Games.
- Android and Linux app visibility can be controlled independently in Settings. Both are enabled by default.
- Built-in wallpapers are shown as full image previews before selection; users can also choose an image from phone storage.
- Customisable themes, accent colours, top panel and dock transparency, icon size, grid density, orientation, pointer/trackpad input and four named workspaces.
- A dock with shortcuts to Applications, GNOME Terminal catalogue, Files, desktop tools and workspaces.

## Linux distributions and GNOME Terminal

The distribution catalogue manages supported ARM64 Linux root filesystems. The Terminal dock action checks for a distribution with the real `gnome-terminal` package. If none is available, the catalogue lets the user install GNOME Terminal into an installed supported distribution.

MT-GNOME does not bundle an Android imitation terminal, its own VT emulator, or a shared host PTY. Linux applications are launched inside the selected distribution using a private mount namespace and a separate `devpts` instance. The Android host's complete `/dev` and host PTY are not shared with the distro process.

A working Linux GUI also requires a running compatible X11 or Wayland session, D-Bus, display sockets, input integration and an Android-hosted surface/window bridge. Installing a Linux GUI package alone does not prove that its window can be displayed. Device validation is required before treating Linux GUI integration as ready.

## Root and BusyBox prerequisite

For the full root-assisted Linux distribution workflow, install and enable a **BusyBox for Android / Magisk BusyBox module** in Magisk, then reboot Android. The BusyBox executable must be available to the root shell or in a standard Magisk module path. BusyBox provides common Unix utilities used by distribution-management and diagnostic shell scripts.

After reboot, verify from a root shell:

```sh
su -c 'command -v busybox || /system/xbin/busybox | head -n 1'
```

MT-GNOME Settings → System & Security → **Magisk BusyBox check** can also test whether BusyBox is discoverable and executable. The base Android desktop launcher can still open without BusyBox, but root-assisted Linux distribution features may be incomplete if it is missing.

**Security note:** BusyBox is a utility collection, not a security boundary. It does not grant root permission by itself, make arbitrary shell commands safe, or replace MT-GNOME's private mount namespace and private `devpts` isolation. Approve root requests only when you understand the action.

## Build

GitHub Actions workflow:

`.github/workflows/mt-gnome-build.yml`

Local build with JDK 17 and Android SDK 36:

```sh
cd android
./gradlew :gnome:assembleDebug
```

The debug APK is generated under `android/gnome/build/outputs/apk/debug/`. Release signing uses the existing NativOS signing secrets; no keystore or passwords are committed.

## Architecture and limitations

- Android application ID: `com.mt.gnome`
- Kotlin and native Android Views
- ARM64 target; Android 14 device testing is the current target
- Embedded X11 and Wayland runtime modules are packaged separately.
- Android continues to own app permissions, task management and actual window policy. Freeform support depends on firmware and user-approved root access.
- MT-GNOME does not install or start MT-Phosh, MT-Phoc, Phosh or Phoc.

The app is a PC-style launcher and desktop shell, not a full desktop Linux session. Do not treat multi-window behaviour, Linux GUI rendering, compositor lifecycle, external-display support or hardware acceleration as validated until tested on the target phone.
