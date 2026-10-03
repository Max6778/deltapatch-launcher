package com.deltapatch.launcher;

import android.content.Context;
import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;

/**
 * Called from the patched WADLoader.ExtractAssetExt (see tools/inject.py).
 * Returns true when that asset was patched by us, so the game must NOT
 * overwrite it with the original copy from the APK.
 */
public final class Hook {
    private Hook() {}

    public static boolean isPatched(Object loader, String path) {
        boolean skip = false;
        try {
            Field f = loader.getClass().getDeclaredField("context");
            f.setAccessible(true);
            Context c = (Context) f.get(loader);
            String n = path == null ? "" : path;
            int i = n.lastIndexOf('/');
            if (i >= 0) n = n.substring(i + 1);
            skip = n.endsWith(".wad") && new File(Store.dir(c, "patched"), n).exists();
            log(c, "ExtractAssetExt(\"" + path + "\") -> " + (skip ? "SKIP (patched)" : "normal"));
        } catch (Throwable t) {
            skip = false;
        }
        return skip;
    }

    private static void log(Context c, String s) {
        try {
            File f = new File(Store.root(c), "hook.log");
            if (f.length() > 64 * 1024) f.delete();
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(System.currentTimeMillis() + " " + s + "\n");
            }
        } catch (Throwable ignored) { }
    }
}
