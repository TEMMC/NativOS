# MT-GNOME

MT-GNOME is a separate native Android HOME launcher in the NativOS repository. It does not replace the MT-OS/Phosh application or change its Linux runtime.

- Application ID: `com.mt.gnome`
- Target: Samsung Galaxy A04e / Android 14 / ARM64
- UI: Kotlin and native Android Views
- Root integration: optional Magisk `su` bridge for Android freeform-mode requests
- Themes: Aurora, Emerald, Midnight
- Signing: reuses the existing NativOS release keystore configuration and GitHub Actions secrets

## Build

From `NativOS/android`:

```bash
./gradlew :gnome:assembleDebug
./gradlew :gnome:assembleRelease
```

The release build reads `signing/keystore.properties`, the same file used by the existing MT-OS module. The MT-GNOME workflow materializes it from `MTOS_KEYSTORE_BASE64`, `MTOS_KEYSTORE_PASSWORD`, `MTOS_KEY_ALIAS`, and `MTOS_KEY_PASSWORD`. No keystore or password is committed.

## Window management

MT-GNOME is a launcher, not a replacement for Android's WindowManager. It requests freeform launch mode and enables relevant global settings through Magisk only when the user selects those controls. Samsung's Android 14 framework may ignore or restrict freeform mode. No boot, recovery, partition, SELinux, or system files are modified.
