# Realme 4K Smart Google TV Stick (Android 14)

Build the debloat list from your own stick, so nothing in it is a guess:

```bash
adb shell pm list packages -3          # apps you installed yourself (keep these)
adb shell pm list packages -s          # system packages (the bloat candidates live here)
adb shell pm list packages -d          # packages that are already disabled
```

Look for the manufacturer's own apps (names with `realme`, `oppo`, `heytap`, `coloros`), ad and telemetry
services, promo / "recommendation" rows and preinstalled apps you never open. When unsure what a package is,
leave it out: disabling is reversible (`tools/restore-adb.sh`), but a bad guess can still cost you a reboot.

Put the ones you pick into `debloat.txt`, one per line, then:

```bash
tools/disable-adb.sh examples/realme-4k-tv-stick/debloat.txt
```

Packages **not** to touch on Google TV: `com.google.android.tvlauncher` / `com.google.android.apps.tv.launcherx`
(the installer script handles the stock launcher), `com.google.android.gms`, `com.google.android.gsf`,
`com.android.vending` (Play Store), `com.android.tv.settings` and anything with `inputmethod`, `webview`,
`permissioncontroller` or `packageinstaller` in the name.
