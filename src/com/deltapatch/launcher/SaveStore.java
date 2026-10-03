package com.deltapatch.launcher;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Finds, backs up and parses Deltarune save files (filechN_M, fileN, ...). */
final class SaveStore {
    private SaveStore() {}

    static final Pattern SAVE = Pattern.compile("^file(ch\\d+_\\d+|\\d+(_\\d+)?)$");
    static final Charset CS = Charset.forName("ISO-8859-1"); // lossless byte round-trip

    static List<File> roots(Context c) {
        List<File> r = new ArrayList<>();
        r.add(c.getFilesDir());
        File ext = c.getExternalFilesDir(null);
        if (ext != null) r.add(ext);
        return r;
    }

    static List<File> find(Context c) {
        List<File> out = new ArrayList<>();
        for (File r : roots(c)) scan(r, 0, out);
        File[] arr = out.toArray(new File[0]);
        Arrays.sort(arr);
        return new ArrayList<>(Arrays.asList(arr));
    }

    private static void scan(File d, int depth, List<File> out) {
        File[] fs = d.listFiles();
        if (fs == null || depth > 3) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                if (f.getName().equals("deltapatch")) continue;
                scan(f, depth + 1, out);
            } else if (SAVE.matcher(f.getName()).matches()) {
                out.add(f);
            }
        }
    }

    /** Folder new/imported saves go to: where existing saves live, otherwise filesDir. */
    static File targetDir(Context c) {
        List<File> l = find(c);
        return l.isEmpty() ? c.getFilesDir() : l.get(0).getParentFile();
    }

    static File backup(Context c) throws IOException {
        List<File> saves = find(c);
        File root = Store.dir(c, "backups");
        File dir = new File(root, "saves-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()));
        for (File f : saves) Store.copy(f, new File(dir, f.getName()));
        prune(root, 20);
        return dir;
    }

    static void backupOne(Context c, File f) throws IOException {
        if (!f.exists()) return;
        File dir = new File(Store.dir(c, "backups"),
                "edit-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()));
        Store.copy(f, new File(dir, f.getName()));
        prune(Store.dir(c, "backups"), 40);
    }

    private static void prune(File root, int keep) {
        File[] d = root.listFiles();
        if (d == null || d.length <= keep) return;
        Arrays.sort(d);
        for (int i = 0; i < d.length - keep; i++) {
            File[] in = d[i].listFiles();
            if (in != null) for (File x : in) x.delete();
            d[i].delete();
        }
    }

    static String nextFreeName(File dir, String name) {
        java.util.regex.Matcher m = Pattern.compile("^(.*_)(\\d+)$").matcher(name);
        if (!m.matches()) return name + "_copy";
        String pre = m.group(1);
        for (int i = Integer.parseInt(m.group(2)) + 1; i < 1000; i++)
            if (!new File(dir, pre + i).exists()) return pre + i;
        return name + "_copy";
    }

    // ---- line model, preserving CRLF and the trailing space Deltarune writes after each value ----

    static final class Doc {
        String eol = "\r\n";
        List<String> lines = new ArrayList<>();
    }

    static Doc load(File f) throws IOException {
        String t = new String(Store.readBytes(f), CS);
        Doc d = new Doc();
        d.eol = t.contains("\r\n") ? "\r\n" : "\n";
        d.lines = new ArrayList<>(Arrays.asList(t.split(java.util.regex.Pattern.quote(d.eol), -1)));
        return d;
    }

    static void store(File f, Doc d) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.lines.size(); i++) {
            if (i > 0) sb.append(d.eol);
            sb.append(d.lines.get(i));
        }
        Store.writeBytes(f, sb.toString().getBytes(CS));
    }

    /** Replace a line's value but keep its original trailing whitespace. */
    static String withSameTail(String original, String typed) {
        int e = original.length();
        while (e > 0 && Character.isWhitespace(original.charAt(e - 1))) e--;
        return typed.trim() + original.substring(e);
    }
}
