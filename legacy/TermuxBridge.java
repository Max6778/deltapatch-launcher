package com.deltapatch.launcher;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;

/** Runs the converter scripts inside Termux through Termux's RUN_COMMAND intent. */
final class TermuxBridge {
    private TermuxBridge() {}

    static final String PKG = "com.termux";
    static final String PERM = "com.termux.permission.RUN_COMMAND";
    static final String BASH = "/data/data/com.termux/files/usr/bin/bash";
    static final String HOME = "/data/data/com.termux/files/home";
    static final String SCRIPT_DIR = "/sdcard/DeltaPatch/termux";

    static boolean installed(Activity a) {
        try {
            a.getPackageManager().getPackageInfo(PKG, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static boolean hasPermission(Activity a) {
        return Build.VERSION.SDK_INT < 23 || a.checkSelfPermission(PERM) == PackageManager.PERMISSION_GRANTED;
    }

    /** Writes the bundled scripts to /sdcard/DeltaPatch/termux so Termux can read them. */
    static void writeScripts() throws IOException {
        File dir = new File(SCRIPT_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + SCRIPT_DIR + " (storage permission?)");
        for (String[] f : TermuxFiles.FILES) {
            try (FileOutputStream os = new FileOutputStream(new File(dir, f[0]))) {
                os.write(f[1].getBytes(Charset.forName("UTF-8")));
                os.flush();
            }
        }
    }

    static void writeJob(int chapter) throws IOException {
        File dir = new File(SCRIPT_DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + SCRIPT_DIR);
        try (FileOutputStream os = new FileOutputStream(new File(dir, "job.conf"))) {
            os.write(("CH=" + chapter + "\n").getBytes(Charset.forName("UTF-8")));
            os.flush();
        }
    }

    /** Starts "bash <script>" in a visible Termux session. Caller checks installed() and hasPermission() first. */
    static void run(Activity a, String scriptName) {
        Intent i = new Intent("com.termux.RUN_COMMAND");
        i.setClassName(PKG, "com.termux.app.RunCommandService");
        i.putExtra("com.termux.RUN_COMMAND_PATH", BASH);
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{SCRIPT_DIR + "/" + scriptName});
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME);
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", false);
        i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
        a.startService(i);
    }

    static final String ENABLE_CMD =
            "mkdir -p ~/.termux && grep -q '^allow-external-apps' ~/.termux/termux.properties 2>/dev/null"
            + " && sed -i 's/^#*allow-external-apps.*/allow-external-apps=true/' ~/.termux/termux.properties"
            + " || echo 'allow-external-apps=true' >> ~/.termux/termux.properties";
}
