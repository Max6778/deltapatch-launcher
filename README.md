# DELTARUNE patch launcher (for Hadrian Soares' Android port)

Adds to the port, with **no PC**: a launcher screen that applies `.xdelta` patches to the
chapter data, adds optional `.ogg` files, backs up saves, and a save manager/editor.

## What you get in the app
* **Top:** list of patches (tick + choose chapter) and optional ogg files. `+ Patch`, `+ OGG`, `Saves`, `Info`.
* **Bottom:** `Launch game (apply patches)` and `Play without patches`.
* **Patching:** source is always the pristine chapter data inside the APK (never an already patched
  file). The patched package is built in a temp file and swapped in only if everything succeeded.
  A wrong-version patch gives a clear error instead of a broken game.
  `Play without patches` drops the patched copy; the game re-extracts the original from the APK.
* **Saves:** automatic timestamped backup before every patch/unpatch/edit. List, import (any file
  picker/provider, via ContentResolver streams), export, duplicate to a new slot, delete (backed up),
  and a line-by-line editor that preserves CRLF and the trailing space after each value.
* **New package name + your own signature**, so it installs next to the original and keeps its saves.

## Separate from your installed game
The build uses package `com.deltapatch.deltarune` (the workflow refuses `com.hadrian.deltarune`),
its own provider authorities, its own signing key and the label "DELTARUNE Patch". Android treats it
as a different app, so it installs next to the original and never touches the original's app, data or
saves. Trade-off: it can't read the original's saves directly; use `Saves > Import` and pick them via
the original's "DELTARUNE savefiles" entry in the file picker (read-only copy).

## Ways to get the Kaizo Knight patch (or any PC mod) running
The patch is built against the PC `data.win`. The launcher supports four routes:
1. **Android-specific patches** (made against this port's `game.droid`): add with `+ Patch`. Works as is.
2. **Full game file:** if someone gives you a complete modded `game.droid`/`data.win`, add it with
   `+ Game file`, tick it, pick the chapter, launch. No patch needed.
3. **PC `data.win` + patch on the phone:** add the PC `data.win` with `+ Game file` (tick, pick chapter),
   add the patch with `+ Patch` (tick, same chapter). The patch is applied on top of that file.
4. **No PC at all:** run the *Cloud patch* workflow. It applies the patch to a `data.win` you uploaded
   to a release, or to your own Steam copy downloaded by steamcmd (needs `STEAM_USER`/`STEAM_PASS`
   secrets, your own purchased copy, and a fresh Steam Guard code typed into the run form). It uploads
   `patched_chapterN_windows.droid` to the release; download it and use route 2.
Routes 2-4 give the game a PC-format file. Whether this port's runner accepts it (and whether touch
controls/chapter switching survive, since those are code edits inside the Android file) is untested.
If it crashes, `Play without patches` puts the original back.

## Build it from your phone (GitHub only)
1. Create a GitHub repo and upload this folder's contents (web "Add file > Upload files", or Termux `git`).
2. Make a release with tag `base` and attach your `base.apk` (limit 2 GB per file).
3. *(Recommended)* make a permanent signing key once, in Termux:
   ```
   pkg install openjdk-17
   keytool -genkeypair -keystore ks.jks -alias key -keyalg RSA -keysize 2048 -validity 36500
   base64 -w0 ks.jks
   ```
   Repo > Settings > Secrets > Actions: add `KEYSTORE_B64` (that output), `KEYSTORE_PASS`
   (the password; use the same for the key), optional `KEY_ALIAS` (default `key`).
   Without them the workflow signs with a throw-away key (works, but you must uninstall before the next build).
4. Actions > "Build DELTARUNE patch-launcher APK" > Run workflow. Download the APK from the run's
   artifacts or from the `base` release.
5. Before first use of the new app, copy any saves you care about out of the original app.
   Then use `Saves > Import` in the new one.

## How it hooks into the game
* `tools/inject.py` renames the package (class names are kept: `libyoyo.so` looks them up by name),
  makes `LauncherActivity` the launcher, and adds a 6-line hook at the start of
  `WADLoader.ExtractAssetExt` so the game's CRC32 check doesn't overwrite a patched chapter with the original.
* The launcher classes are compiled with javac + d8 and added as an extra `classesN.dex`.

## NOT verified (I had no Android SDK, apktool or device)
* The Java sources parse, and the VCDIFF decoder is tested (it reproduces a reference decoder
  byte-for-byte and consumes every byte of the Kaizo Knight patch). Nothing else was compiled or run.
* **Chapter location is inferred from bytecode:** the game copies `chapterN.wad` into the app cache
  dir. If the game reads chapters from somewhere else, the patch has no effect. `Info` shows what the
  game extracted (`hook.log`); check it after one launch of a chapter.
* **Kaizo Knight will not apply to this APK's Android data.** I ran it against the Android chapter 3
  `game.droid`: every window fails its checksum (the patch targets the PC `data.win`; the Android
  file is a different size and layout). You need a patch built for the Android file. `Ignore
  checksum` exists but produces a broken game for such patches.
* OGG default folder is `assets/mus/` inside the chapter package (guess; editable in the app).
* The save editor shows raw lines (no field names): I only had one sample save.

## Test checklist
patch a chapter / "Play without patches" / wrong-version patch shows the error / import / edit +
backup created / duplicate to new slot / export / start game normally.
