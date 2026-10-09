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
            if (n.toLowerCase(java.util.Locale.ROOT).endsWith(".ogg")) {
                // Mod music with the same name as a game song: keep ours, do not restore the original.
                skip = modMusic(c).contains(n.toLowerCase(java.util.Locale.ROOT));
                if (skip) CrashLog.note(c, "game asks for " + n + " -> keeping the mod music");
            }
        } catch (Throwable t) {
            skip = false;
        }
        return skip;
    }

    private static java.util.Set<String> modMusic(Context c) {
        java.util.Set<String> out = new java.util.HashSet<>();
        try {
            File list = new File(Store.dir(c, "built"), "music.list");
            if (list.isFile())
                for (String l : new String(Store.readBytes(list), "UTF-8").split("\n"))
                    if (!l.trim().isEmpty()) out.add(l.trim().toLowerCase(java.util.Locale.ROOT));
        } catch (Throwable ignored) { }
        return out;
    }
}
