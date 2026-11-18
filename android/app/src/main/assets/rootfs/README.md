# MT-OS Debian rootfs release asset

The Debian ARM64 root filesystem is deliberately **not bundled inside the Android APK**.
The Android build creates a versioned GitHub Release asset named
`mt-os-rootfs-arm64.tgz` and embeds only its HTTPS URL and SHA-256 digest in the APK.

The matching MT-OS app downloads the archive on first setup, resumes interrupted
downloads using HTTP Range, verifies SHA-256, and extracts it into the app's
private data directory. After setup, the desktop remains available offline.

The build workflow generates the archive from the prepared Debian ARM64 rootfs:

```sh
scripts/build-rootfs-asset.sh /path/to/prepared-rootfs /path/to/mt-os-rootfs-arm64.tgz
```

The prepared image must contain the MT-Phoc and MT-Phosh builds, GNOME Console,
required schemas, icons, and MT-OS compatibility libraries. The script normalizes
ownership, ordering, timestamps and gzip metadata and records the package set.
