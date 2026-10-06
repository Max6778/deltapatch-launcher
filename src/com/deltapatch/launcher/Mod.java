package com.deltapatch.launcher;

import android.content.Context;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

/** One installed mod: mods/<id>/patch.xdelta, optional mods/<id>/ogg/*.ogg and mod.properties. */
final class Mod {
    final String id;
    final File dir;
    String name;
    int chapter;
    boolean enabled;
    boolean gmlTouch;

    private Mod(String id, File dir) {
        this.id = id;
        this.dir = dir;
    }

    File patch() { return new File(dir, "patch.xdelta"); }

    File oggDir() { return new File(dir, "ogg"); }

    List<File> oggs() {
        List<File> out = new ArrayList<>();
        File[] fs = oggDir().listFiles();
        if (fs != null) {
            Arrays.sort(fs);
            for (File f : fs) if (f.isFile()) out.add(f);
        }
        return out;
    }

    static File root(Context c) { return Store.dir(c, "mods"); }

    static List<Mod> list(Context c) {
        List<Mod> out = new ArrayList<>();
        File[] ds = root(c).listFiles();
        if (ds == null) return out;
        Arrays.sort(ds);
        for (File d : ds) {
            File pf = new File(d, "mod.properties");
            if (!d.isDirectory() || !pf.isFile() || !new File(d, "patch.xdelta").isFile()) continue;
            try (FileInputStream in = new FileInputStream(pf)) {
                Properties p = new Properties();
                p.load(in);
                Mod m = new Mod(d.getName(), d);
                m.name = p.getProperty("name", d.getName());
                m.chapter = Integer.parseInt(p.getProperty("chapter", "3"));
                m.enabled = Store.prefs(c).getBoolean("modon." + d.getName(), false);
                m.gmlTouch = Boolean.parseBoolean(p.getProperty("gmltouch", "false"));
                out.add(m);
            } catch (Exception ignored) { }
        }
        return out;
    }

    static Mod create(Context c, String fileName, int chapter) throws IOException {
        String base = fileName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        String clean = base.replaceAll("[^A-Za-z0-9_\\- ]", "_").trim();
        if (clean.isEmpty()) clean = "mod";
        String id = clean.replace(' ', '_') + "_ch" + chapter;
        File d = new File(root(c), id);
        int n = 2;
        while (d.exists()) {
            d = new File(root(c), id + "_" + n);
            n++;
        }
        if (!d.mkdirs()) throw new IOException("Cannot create " + d);
        Properties p = new Properties();
        p.setProperty("name", base);
        p.setProperty("chapter", String.valueOf(chapter));
        boolean gml = fileName.toLowerCase(java.util.Locale.ROOT).contains("android");
        p.setProperty("gmltouch", String.valueOf(gml));
        try (FileOutputStream os = new FileOutputStream(new File(d, "mod.properties"))) {
            p.store(os, "DeltaPatch mod");
        }
        Mod m = new Mod(d.getName(), d);
        m.name = base;
        m.chapter = chapter;
        m.enabled = true;
        m.gmlTouch = gml;
        Store.prefs(c).edit().putBoolean("modon." + m.id, true).apply();
        return m;
    }

    void setEnabled(Context c, boolean on) {
        enabled = on;
        Store.prefs(c).edit().putBoolean("modon." + id, on).apply();
    }

    void delete(Context c) {
        File[] all = oggDir().listFiles();
        if (all != null) for (File f : all) f.delete();
        oggDir().delete();
        File[] fs = dir.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        dir.delete();
        Store.prefs(c).edit().remove("modon." + id).apply();
    }
}
