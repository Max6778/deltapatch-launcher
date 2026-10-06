package com.deltapatch.launcher;

import android.content.Context;
import android.os.Build;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Writes a readable log file when the launcher or the game crashes (Android/data/<pkg>/files/DeltaPatch/logs). */
public final class CrashLog {
    private CrashLog() {}

    static File dir(Context c) { return Store.dir(c, "logs"); }

    /** Installed from the launcher and (through a smali hook) at the start of the game activity. */
    public static void install(Context ctx) {
        try {
            Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
            Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
            if (prev instanceof Handler) return;
            Thread.setDefaultUncaughtExceptionHandler(new Handler(app, prev));
        } catch (Throwable ignored) { }
    }

    private static final class Handler implements Thread.UncaughtExceptionHandler {
        private final Context app;
        private final Thread.UncaughtExceptionHandler prev;

        Handler(Context app, Thread.UncaughtExceptionHandler prev) {
            this.app = app;
            this.prev = prev;
        }

        @Override
        public void uncaughtException(Thread t, Throwable e) {
            try { write(app, "CRASH in thread " + t.getName(), e); } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        }
    }

    static File write(Context c, String title, Throwable e) {
        try {
            String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            File f = new File(dir(c), "crash-" + stamp + ".txt");
            StringBuilder sb = new StringBuilder();
            sb.append(title).append("\n");
            sb.append("Time: ").append(new Date()).append("\n");
            sb.append("Package: ").append(c.getPackageName()).append("\n");
            sb.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
            sb.append("Enabled mods: ");
            try {
                for (Mod m : Mod.list(c)) if (m.enabled) sb.append(m.name).append(" (ch").append(m.chapter).append(") ");
            } catch (Throwable ignored) { }
            sb.append("\n\n");
            if (e != null) {
                StringWriter sw = new StringWriter();
                e.printStackTrace(new PrintWriter(sw));
                sb.append(sw).append("\n");
            }
            sb.append("---- last log lines of this app ----\n").append(logcat());
            try (FileWriter w = new FileWriter(f)) { w.write(sb.toString()); }
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    private static String logcat() {
        StringBuilder sb = new StringBuilder();
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"logcat", "-d", "-t", "300"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            int n = 0;
            while ((line = r.readLine()) != null && n < 400) {
                sb.append(line).append('\n');
                n++;
            }
        } catch (IOException ignored) {
        } finally {
            if (p != null) p.destroy();
        }
        return sb.toString();
    }

    /** Appends one line to logs/launcher.log. */
    static void note(Context c, String line) {
        try {
            File f = new File(dir(c), "launcher.log");
            if (f.length() > 128 * 1024) f.delete();
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date()) + "  " + line + "\n");
            }
        } catch (Throwable ignored) { }
    }
}
