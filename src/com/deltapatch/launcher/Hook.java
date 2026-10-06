package com.deltapatch.launcher;

import android.content.Context;
import java.io.File;
import java.lang.reflect.Field;

/**
 * Called from the patched WADLoader.ExtractAssetExt (see tools/inject.py).
 * Returns true when that chapter package was built by us, so the game must NOT overwrite it with the original.
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
            if (n.endsWith(".wad")) CrashLog.note(c, "game asks for " + n + " -> " + (skip ? "using the patched copy" : "original"));
        } catch (Throwable t) {
            skip = false;
        }
        return skip;
    }
}
