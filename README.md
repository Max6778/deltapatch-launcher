# DELTARUNE patch launcher (Hadrian's Android port)

Personal-use tool. It bundles your own PC `data.win` files, applies `.xdelta` mods in the launcher, keeps your
saves backed up and writes a log when something crashes. Do not share an APK that contains game files.

## Using it
(Update steps from an older version: `docs/UPDATE-STEPS.md`.)
* **Add mod** > pick the chapter > pick the `.xdelta` (> optionally pick `.ogg` music). Tick the mods you want.
* **PLAY** builds the patched chapter from the bundled PC `data.win` (first time takes a minute, then it is cached)
  and starts the game. **Play original** starts the unmodified game.
* **Restore** switches all mods off and removes the patched files. The originals live inside the APK and are never
  modified. Saves are backed up automatically before every play, restore and edit (`Saves` also imports/exports/edits).
* **Logs**: crash reports and the launcher log. Tap to read, Share to send.
* Everything the launcher manages is in `Android/data/<package>/files/DeltaPatch` (mods, backups, logs, built files),
  reachable with ZArchiver or a Shizuku-enabled file manager. The game's working copy is in the app's private cache.

## Build (GitHub Actions, private repo)
0. (Once) Run "Bundle PC data.win into base.apk": it adds your `chapterN.win` files to a copy of `base.apk` and uploads
   `base-bundled.apk`; later builds use it and don't download the .win files again.
1. Release `base`: attach `base.apk` and your PC files named `chapter1.win` ... `chapter5.win` (any subset).
2. Actions > "Build DELTARUNE patch-launcher APK" > Run. The workflow bundles `chapterN.win` into `assets/pc/`.
3. Keep the repository private. Secrets `KEYSTORE_B64`, `KEYSTORE_PASS` (alias `key`) keep the signature stable.

## What changed in this version
* Fixes the start-up crash "Unable to locate assets": the port looked up the APK by the hard-coded original package
  name; the injector now rewrites that one literal to the new package.
* No microphone permission/pop-up. Simplified screen. Mods are managed (add / tick / remove / restore).
* Touch buttons (arrows, Z, X, C) are drawn over chapters built from a PC file, because the PC game has no touch UI.

## Porting a PC mod to Android (touch controls included) - GitHub only
Release `base` (private repo) holds: `base.apk`, `chapterN.win`, and the raw PC mod patch (e.g. `kaizo_knight.xdelta`).
Run Actions > **"Port a PC mod to Android"** (chapter, mod patch name, output name). It applies the mod to the PC file,
runs `scripts/AndroidPort.csx` with UndertaleModCli (copies the 13 touch sprites, 3 objects and 18 scripts from the
port's own file, calls `scr_init_touch_controls()`, fixes the texture prefetch and the music folder), then makes a small
`<name>_android.xdelta` against the bundled PC file and checks it. In the launcher: **Add mod**, pick that file
(keep "android" in its name so the Java overlay stays off). Not copied: `load_wad` (in-game chapter switching).

## Known limits (honest)
* Touch UI research: see `docs/touch-controls-research.md`.
* A raw PC mod patch yields a PC-format game file without touch controls; use the port workflow above for those.
  `AndroidPort.csx` was written against the UndertaleModTool source but has never been run: expect a fix-up round.
* In-game chapter switching of a PC-format chapter is not handled; go back and pick the chapter from the menu.
* The touch buttons send keyboard events; whether the runner accepts them is untested.
* Optional cloud tools (`cloud-patch.yml`, `decompile-diff.yml`, `port-android.yml`) and the old Termux/GitHub wizard
  (folder `legacy/`) are kept for advanced use but are not part of the app any more.
