package com.deltapatch.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Paths, preferences and small file helpers shared by all screens. */
final class Store {
    private Store() {}

    /**
     * Everything the launcher manages lives in Android/data/<package>/files/DeltaPatch when external storage is
     * available (reachable with ZArchiver / Shizuku-enabled file managers), otherwise in the private files dir.
     */
    static File root(Context c) {
        File ext = c.getExternalFilesDir(null);
        File base = ext != null ? ext : c.getFilesDir();
        File f = new File(base, "DeltaPatch");
        f.mkdirs();
        return f;
    }

    static File dir(Context c, String name) {
        File f = new File(root(c), name);
        f.mkdirs();
        return f;
    }

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("deltapatch", Context.MODE_PRIVATE);
    }

    /** Where the game keeps the extracted chapter package (inferred from WADLoader.ExtractAssetExt). */
    static File wadFile(Context c, int chapter) {
        return new File(c.getCacheDir(), "chapter" + chapter + ".wad");
    }

    static String displayName(Context c, Uri u) {
        String name = null;
        try (Cursor cur = c.getContentResolver().query(u, null, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                int i = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) name = cur.getString(i);
            }
        } catch (Exception ignored) { }
        if (name == null || name.isEmpty()) {
            name = u.getLastPathSegment();
            if (name == null) name = "file";
        }
        int s = Math.max(name.lastIndexOf('/'), name.lastIndexOf(':'));
        if (s >= 0) name = name.substring(s + 1);
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /** Copy a SAF uri into dir using ContentResolver streams (works with any provider). */
    static File copyFromUri(Context c, Uri u, File dir) throws IOException {
        File out = new File(dir, displayName(c, u));
        try (InputStream in = c.getContentResolver().openInputStream(u)) {
            if (in == null) throw new IOException("Cannot open " + u);
            copy(in, out);
        }
        return out;
    }

    static void copy(InputStream in, File out) throws IOException {
        File parent = out.getParentFile();
        if (parent != null) parent.mkdirs();
        try (OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
            os.flush();
        }
    }

    static void copy(File in, File out) throws IOException {
        try (InputStream is = new java.io.FileInputStream(in)) {
            copy(is, out);
        }
    }

    static byte[] readBytes(File f) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        }
    }

    static void writeBytes(File f, byte[] data) throws IOException {
        try (FileOutputStream os = new FileOutputStream(f)) {
            os.write(data);
            os.flush();
        }
    }

    static String human(long b) {
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return (b / 1024) + " KB";
        return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
    }
}
