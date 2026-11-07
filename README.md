# GRABVR Mod Manager (Quest)

Native Android app for the Quest. The screen is `app/src/main/assets/index.html`.
Targets the game `com.slindev.grab_demo` (single base.apk, no OBB).

## Build without installing anything (GitHub Actions)
1. Create a free GitHub repository and upload everything in this folder
   (the hidden `.github/workflows/build.yml` file must be included).
2. Open the repository's **Actions** tab, open the latest "Build APK" run (or press "Run workflow").
3. When it finishes, download the **GrabVR-Manager-apk** artifact. Unzip it to get `app-debug.apk`.
   (The compiled `classes.dex` is inside that APK.)
4. Install the APK on the Quest (SideQuest, or `adb install app-debug.apk`).
   If the first build fails while downloading `libadb-android`, run it again; JitPack builds it on first request.

## First use on the headset
1. Settings > System > Developer: turn on **Wireless debugging**, tap **Pair device with code**.
2. In the app, Setup tab: enter the pairing port and code, and the connect port from the main Wireless debugging screen, then **Pair**.
3. **Save original game file** (needs no ADB), then use Mods / Levels / Play.

## What works
| Feature | Needs wireless ADB |
|---|---|
| Save original game file (copies the installed apk to base.apk) | no |
| Manually save original game file (pick the .apk; checked against com.slindev.grab_demo) | no |
| Launch | no |
| Stop game | yes (otherwise only background processes are killed) |
| Import / list / remove .level files | yes |
| Restore original base (uninstall + reinstall base.apk, resets game data) | yes |
| Mod list, enable/disable, install from file, store | no |
| **Apply mods** | **not built: the mod format is undecided** |

Store format: `<store url>/manifest.json` =
`{"mods":[{"id":"x","name":"X","version":"1.0","description":"...","url":"x.zip"}]}`

Files: `Adb.kt` (wireless ADB), `Game.kt` (base, launch, levels), `Store.kt` (mods, store), `Bridge.kt` (page <-> native).
