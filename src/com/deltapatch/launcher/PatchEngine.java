package com.deltapatch.launcher;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Applies xdelta patches to assets/game.droid inside the chapterN.wad package.
 * The pristine source is ALWAYS the copy inside the APK, so patching is repeatable
 * and "play without patches" simply drops our patched copy.
 */
final class PatchEngine {
    private PatchEngine() {}

    interface Log { void log(String s); }

    static final String GAME_ENTRY = "assets/game.droid";

    static void applyAll(Context c, boolean ignoreChecksum, String oggPrefix,
                         Vcdiff.Progress prog, Log log) throws IOException {
        Map<Integer, List<File>> byCh = new TreeMap<>();
        File[] ps = Store.dir(c, "patches").listFiles();
        if (ps != null) {
            Arrays.sort(ps);
            for (File f : ps) {
                if (!Store.prefs(c).getBoolean("on." + f.getName(), false)) continue;
                int ch = Store.prefs(c).getInt("ch." + f.getName(), 3);
                List<File> l = byCh.get(ch);
                if (l == null) { l = new ArrayList<>(); byCh.put(ch, l); }
                l.add(f);
            }
        }
        Map<Integer, File> bases = new TreeMap<>();
        File[] bs = Store.dir(c, "sources").listFiles();
        if (bs != null) {
            Arrays.sort(bs);
            for (File f : bs) {
                if (!Store.prefs(c).getBoolean("on." + f.getName(), false)) continue;
                bases.put(Store.prefs(c).getInt("ch." + f.getName(), 3), f);
            }
        }
        List<File> oggs = new ArrayList<>();
        File[] os = Store.dir(c, "ogg").listFiles();
        if (os != null) {
            Arrays.sort(os);
            for (File f : os) if (Store.prefs(c).getBoolean("on." + f.getName(), false)) oggs.add(f);
        }

        log.log("Backing up save files...");
        SaveStore.backup(c);

        for (int ch = 0; ch <= 5; ch++) {
            if (!byCh.containsKey(ch) && !bases.containsKey(ch)) {
                if (isPatched(c, ch)) { log.log("Chapter " + ch + ": restoring original"); unpatch(c, ch); }
            }
        }
        java.util.TreeSet<Integer> chapters = new java.util.TreeSet<>(byCh.keySet());
        chapters.addAll(bases.keySet());
        for (int ch : chapters) {
            List<File> l = byCh.get(ch);
            if (l == null) l = new ArrayList<>();
            patchChapter(c, ch, bases.get(ch), l, oggs, oggPrefix, ignoreChecksum, prog, log);
        }
        log.log("Done.");
    }

    static void unpatchAll(Context c, Log log) {
        for (int ch = 0; ch <= 5; ch++) {
            if (isPatched(c, ch)) { log.log("Chapter " + ch + ": restoring original"); unpatch(c, ch); }
        }
    }

    static boolean isPatched(Context c, int ch) {
        return new File(Store.dir(c, "patched"), "chapter" + ch + ".wad").exists();
    }

    /** Dropping our copy makes the game's own ExtractAssetExt re-copy the original from the APK. */
    static void unpatch(Context c, int ch) {
        new File(Store.dir(c, "patched"), "chapter" + ch + ".wad").delete();
        Store.wadFile(c, ch).delete();
    }

    /**
     * base == null: start from the game's own game.droid (inside the APK).
     * base != null: start from that file instead - either a complete replacement game file
     * (no patches ticked) or a patch source such as a PC data.win.
     */
    private static void patchChapter(Context c, int ch, File base, List<File> patches, List<File> oggs,
                                     String oggPrefix, boolean ignore, Vcdiff.Progress prog, Log log) throws IOException {
        String asset = "chapter" + ch + ".wad";
        File tmp = Store.dir(c, "tmp");
        File a = new File(tmp, "a.droid"), b = new File(tmp, "b.droid"), nw = new File(tmp, "new.wad");
        try {
            checkSpace(c, asset, tmp);
            File cur;
            if (base != null) {
                log.log("Chapter " + ch + ": using " + base.getName() + " as game data");
                cur = base;
            } else {
                log.log("Chapter " + ch + ": extracting original game data...");
                extractEntry(c, asset, GAME_ENTRY, a);
                cur = a;
            }
            for (File p : patches) {
                long need = Vcdiff.requiredSourceSize(p);
                if (cur.length() < need)
                    throw new IOException(p.getName() + " expects a source file of at least " + need
                            + " bytes (" + Store.human(need) + ") but the base data for chapter " + ch
                            + " is " + cur.length() + " bytes. The patch was made for another file"
                            + " (probably the PC data.win). Add that file with + Game file and tick it.");
                File next = (cur == a) ? b : a;
                log.log("Chapter " + ch + ": applying " + p.getName() + "...");
                Vcdiff.apply(p, cur, next, ignore, prog);
                cur = next;
            }
            log.log("Chapter " + ch + ": building patched package...");
            rebuildWad(c, asset, cur, oggs, oggPrefix, nw);
            File dest = Store.wadFile(c, ch);
            File parent = dest.getParentFile();
            if (parent != null) parent.mkdirs();
            if (!nw.renameTo(dest)) {
                Store.copy(nw, dest);
            }
            new File(Store.dir(c, "patched"), asset).createNewFile();
            log.log("Chapter " + ch + ": patched OK.");
        } finally {
            a.delete(); b.delete(); nw.delete();
        }
    }

    private static void checkSpace(Context c, String asset, File dir) throws IOException {
        long size = 400L << 20;
        try (AssetFileDescriptor fd = c.getAssets().openFd(asset)) {
            size = Math.max(fd.getLength() * 4, 64L << 20);
        } catch (Exception ignored) { }
        long free = new StatFs(dir.getAbsolutePath()).getAvailableBytes();
        if (free < size)
            throw new IOException("Not enough free space: about " + Store.human(size)
                    + " needed, " + Store.human(free) + " available.");
    }

    private static void extractEntry(Context c, String asset, String entry, File out) throws IOException {
        try (InputStream raw = c.getAssets().open(asset, android.content.res.AssetManager.ACCESS_STREAMING);
             ZipInputStream zin = new ZipInputStream(new BufferedInputStream(raw, 1 << 16))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.getName().equals(entry)) { Store.copy(zin, out); return; }
            }
        }
        throw new IOException(entry + " not found in " + asset);
    }

    private static void rebuildWad(Context c, String asset, File game, List<File> oggs, String oggPrefix,
                                   File out) throws IOException {
        Set<String> seen = new HashSet<>();
        try (InputStream raw = c.getAssets().open(asset, android.content.res.AssetManager.ACCESS_STREAMING);
             ZipInputStream zin = new ZipInputStream(new BufferedInputStream(raw, 1 << 16));
             ZipOutputStream zout = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(out), 1 << 18))) {
            zout.setLevel(1);
            byte[] buf = new byte[1 << 16];
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName();
                if (e.isDirectory()) continue;
                seen.add(name);
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
            String prefix = oggPrefix == null ? "" : oggPrefix.trim();
            if (!prefix.isEmpty() && !prefix.endsWith("/")) prefix += "/";
            for (File f : oggs) {
                String name = prefix + f.getName();
                if (seen.contains(name)) continue;
                zout.putNextEntry(new ZipEntry(name));
                try (InputStream in = new FileInputStream(f)) {
                    int n;
                    while ((n = in.read(buf)) > 0) zout.write(buf, 0, n);
                }
                zout.closeEntry();
            }
        }
    }
}
