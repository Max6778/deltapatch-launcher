package com.deltapatch.launcher;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.AssetManager;
import android.os.StatFs;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Builds the patched chapterN.wad packages.
 * Source of truth is always inside the APK (the bundled PC file assets/pc/chapterN.win, or the Android
 * game.droid inside assets/chapterN.wad), so the original files are never modified and "restore" just drops our copy.
 */
final class PatchEngine {
    private PatchEngine() {}

    interface Log { void log(String s); }

    static final String GAME_ENTRY = "assets/game.droid";

    // ------------------------------------------------------------------ public API

    /** Names of the bundled PC files for a chapter: chapterN.win and chapterN_<version>.win (several game versions). */
    static List<String> bundledNames(Context c, int ch) {
        List<String> out = new ArrayList<>();
        try {
            String[] names = c.getAssets().list("pc");
            if (names != null) {
                java.util.Arrays.sort(names);
                for (String n : names)
                    if (n.equals("chapter" + ch + ".win") || (n.startsWith("chapter" + ch + "_") && n.endsWith(".win"))) out.add(n);
            }
        } catch (IOException ignored) { }
        return out;
    }

    /** Chapters for which a PC data.win is bundled in the APK. */
    static List<Integer> bundledChapters(Context c) {
        List<Integer> out = new ArrayList<>();
        for (int ch = 0; ch <= 5; ch++) if (!bundledNames(c, ch).isEmpty()) out.add(ch);
        return out;
    }

    static void apply(Context c, List<Mod> mods, Vcdiff.Progress prog, Log log) throws IOException {
        Map<Integer, List<Mod>> byCh = new TreeMap<>();
        for (Mod m : mods) {
            if (!m.enabled) continue;
            List<Mod> l = byCh.get(m.chapter);
            if (l == null) {
                l = new ArrayList<>();
                byCh.put(m.chapter, l);
            }
            l.add(m);
        }
        log.log("Backing up your save files...");
        SaveStore.backup(c);
        for (int ch = 0; ch <= 5; ch++)
            if (!byCh.containsKey(ch) && isPatched(c, ch)) unpatch(c, ch);
        copyMusic(c, mods);
        for (Map.Entry<Integer, List<Mod>> e : byCh.entrySet()) buildChapter(c, e.getKey(), e.getValue(), prog, log);
        log.log("Ready.");
    }

    /** Play without mods: the game re-extracts its original chapter files from the APK. */
    static void unpatchAll(Context c) {
        for (int ch = 0; ch <= 5; ch++) unpatch(c, ch);
        removeMusic(c);
    }

    /** Restore button: unpatch, forget the built copies and switch every mod off. */
    static void restoreOriginal(Context c) {
        unpatchAll(c);
        File[] bs = Store.dir(c, "built").listFiles();
        if (bs != null) for (File f : bs) f.delete();
        File[] ts = Store.dir(c, "tmp").listFiles();
        if (ts != null) for (File f : ts) f.delete();
        for (Mod m : Mod.list(c)) m.setEnabled(c, false);
    }

    static boolean isPatched(Context c, int ch) {
        return new File(Store.dir(c, "patched"), "chapter" + ch + ".wad").exists();
    }

    // ------------------------------------------------------------------ internals

    /** Flag file: this chapter already has the game's own touch controls, so the Java overlay stays off. */
    private static void setGmlTouch(Context c, int ch, List<Mod> mods) throws IOException {
        File flag = new File(Store.dir(c, "patched"), "chapter" + ch + ".gmltouch");
        boolean any = false;
        for (Mod m : mods) if (m.gmlTouch) any = true;
        if (any) flag.createNewFile(); else flag.delete();
    }

    private static void unpatch(Context c, int ch) {
        new File(Store.dir(c, "patched"), "chapter" + ch + ".gmltouch").delete();
        new File(Store.dir(c, "patched"), "chapter" + ch + ".wad").delete();
        Store.wadFile(c, ch).delete();
    }

    private static File musicList(Context c) {
        return new File(Store.dir(c, "built"), "music.list");
    }

    /** Music folders the game may read: the Android build uses <cache>/mus, the PC code uses "mus/" relative to its working folder. */
    private static List<File> musicDirs(Context c) {
        List<File> out = new ArrayList<>();
        out.add(new File(c.getCacheDir(), "mus"));
        out.add(new File(c.getFilesDir(), "mus"));
        File ext = c.getExternalFilesDir(null);
        if (ext != null) out.add(new File(ext, "mus"));
        return out;
    }

    /** Extra music is copied to every folder the game might read (the exact one differs between PC and Android code). */
    private static void copyMusic(Context c, List<Mod> mods) throws IOException {
        removeMusic(c);
        List<String> added = new ArrayList<>();
        for (Mod m : mods) {
            if (!m.enabled) continue;
            for (File f : m.oggs()) {
                for (File dir : musicDirs(c)) {
                    dir.mkdirs();
                    Store.copy(f, new File(dir, f.getName()));
                }
                added.add(f.getName());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String s : added) sb.append(s).append('\n');
        Store.writeBytes(musicList(c), sb.toString().getBytes("UTF-8"));
    }

    private static void removeMusic(Context c) {
        File list = musicList(c);
        if (!list.isFile()) return;
        try {
            String[] names = new String(Store.readBytes(list), "UTF-8").split("\n");
            for (String n : names)
                if (!n.trim().isEmpty()) for (File dir : musicDirs(c)) new File(dir, n.trim()).delete();
        } catch (IOException ignored) { }
        list.delete();
    }

    private static String signature(List<Mod> mods) {
        StringBuilder sb = new StringBuilder();
        for (Mod m : mods) {
            sb.append(m.id).append(':').append(m.patch().length()).append(':').append(m.patch().lastModified()).append(';');
        }
        return String.valueOf(sb.toString().hashCode()) + "-" + sb.length();
    }

    private static String readText(File f) {
        try { return new String(Store.readBytes(f), "UTF-8").trim(); } catch (Exception e) { return ""; }
    }

    private static void buildChapter(Context c, int ch, List<Mod> mods, Vcdiff.Progress prog, Log log) throws IOException {
        String asset = "chapter" + ch + ".wad";
        File built = new File(Store.dir(c, "built"), asset);
        File sigFile = new File(Store.dir(c, "built"), "chapter" + ch + ".sig");
        File dest = Store.wadFile(c, ch);
        File marker = new File(Store.dir(c, "patched"), asset);
        String sig = signature(mods);

        if (built.isFile() && sig.equals(readText(sigFile))) {
            log.log("Chapter " + ch + ": using the file built earlier");
            if (!dest.isFile() || dest.length() != built.length()) Store.copy(built, dest);
            marker.createNewFile();
            setGmlTouch(c, ch, mods);
            return;
        }

        File tmp = Store.dir(c, "tmp");
        File a = new File(tmp, "a.bin"), b = new File(tmp, "b.bin"), nw = new File(tmp, "new.wad");
        try {
            checkSpace(c, asset, tmp);
            List<String> bases = bundledNames(c, ch);
            int total = bases.size() + 1; // every bundled PC version, then the Android game data
            StringBuilder tried = new StringBuilder();
            File cur = null;
            for (int attempt = 0; attempt < total && cur == null; attempt++) {
                boolean usePc = attempt < bases.size();
                boolean last = attempt == total - 1;
                a.delete();
                b.delete();
                String label;
                if (usePc) {
                    String n = bases.get(attempt);
                    log.log("Chapter " + ch + ": reading the bundled " + n + "...");
                    copyAsset(c, "pc/" + n, a);
                    label = n + " (" + a.length() + " bytes)";
                } else {
                    log.log("Chapter " + ch + ": reading the Android game data...");
                    extractEntry(c, asset, GAME_ENTRY, a);
                    label = "the Android game data (" + a.length() + " bytes)";
                }
                if (tried.length() > 0) tried.append(", ");
                tried.append(label);
                File work = a;
                boolean fits = true;
                for (Mod m : mods) {
                    File next = (work == a) ? b : a;
                    log.log("Chapter " + ch + ": applying " + m.name + " to " + label + "...");
                    try {
                        Vcdiff.apply(m.patch(), work, next, false, prog);
                    } catch (Vcdiff.ChecksumMismatch e) {
                        if (!last) {
                            log.log("\"" + m.name + "\" does not fit that file, trying the next one...");
                            fits = false;
                            break;
                        }
                        long need = 0;
                        try { need = Vcdiff.requiredSourceSize(m.patch()); } catch (IOException ignored) { }
                        throw new IOException("\"" + m.name + "\" does not fit chapter " + ch + ": it was made for a different game version."
                                + (need > 0 ? " It needs a game file of at least " + need + " bytes." : "")
                                + " Tried: " + tried + ". Add the matching data.win to the build as chapter" + ch + "_<version>.win.");
                    }
                    work = next;
                }
                if (fits) {
                    cur = work;
                    log.log("Chapter " + ch + ": the mod fits " + label);
                }
            }
            log.log("Chapter " + ch + ": packing the game files...");
            rebuildWad(c, asset, cur, nw);
            if (built.exists()) built.delete();
            if (!nw.renameTo(built)) Store.copy(nw, built);
            Store.writeBytes(sigFile, sig.getBytes("UTF-8"));
            Store.copy(built, dest);
            marker.createNewFile();
            setGmlTouch(c, ch, mods);
            log.log("Chapter " + ch + ": done.");
        } finally {
            a.delete();
            b.delete();
            nw.delete();
        }
    }

    private static void checkSpace(Context c, String asset, File dir) throws IOException {
        long need = 600L << 20;
        try (AssetFileDescriptor fd = c.getAssets().openFd(asset)) {
            need = Math.max(fd.getLength() * 4, 400L << 20);
        } catch (Exception ignored) { }
        long free = new StatFs(dir.getAbsolutePath()).getAvailableBytes();
        if (free < need)
            throw new IOException("Not enough free space: about " + Store.human(need) + " needed, "
                    + Store.human(free) + " available.");
    }

    private static void copyAsset(Context c, String name, File out) throws IOException {
        try (InputStream in = new BufferedInputStream(c.getAssets().open(name, AssetManager.ACCESS_STREAMING), 1 << 16)) {
            Store.copy(in, out);
        }
    }

    static void extractEntry(Context c, String asset, String entry, File out) throws IOException {
        try (InputStream raw = c.getAssets().open(asset, AssetManager.ACCESS_STREAMING);
             ZipInputStream zin = new ZipInputStream(new BufferedInputStream(raw, 1 << 16))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.getName().equals(entry)) {
                    Store.copy(zin, out);
                    return;
                }
            }
        }
        throw new IOException(entry + " not found in " + asset);
    }

    private static void rebuildWad(Context c, String asset, File game, File out) throws IOException {
        try (InputStream raw = c.getAssets().open(asset, AssetManager.ACCESS_STREAMING);
             ZipInputStream zin = new ZipInputStream(new BufferedInputStream(raw, 1 << 16));
             ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out), 1 << 18))) {
            zout.setLevel(1);
            byte[] buf = new byte[1 << 16];
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory()) continue;
                zout.putNextEntry(new ZipEntry(name));
                if (name.equals(GAME_ENTRY)) {
                    try (InputStream in = new FileInputStream(game)) {
                        int n;
                        while ((n = in.read(buf)) > 0) zout.write(buf, 0, n);
                    }
                } else {
                    int n;
                    while ((n = zin.read(buf)) > 0) zout.write(buf, 0, n);
                }
                zout.closeEntry();
            }
        }
    }
}
