package com.deltapatch.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Simple launcher: list of mods on top, Play / Play original at the bottom. */
public class LauncherActivity extends Activity {
    private static final int REQ_PATCH = 1, REQ_OGG = 2;
    private static final String GAME = "com.hadrian.deltarune.RunnerActivity";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout modList;
    private TextView status, crashNote, bundleNote;
    private ProgressBar bar;
    private Button play, original;
    private volatile boolean busy;
    private int pendingChapter = 3;
    private Mod pendingMod;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        CrashLog.install(this);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        int p = dp(12);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("DELTARUNE mods");
        title.setTextSize(22);
        root.addView(title);

        bundleNote = new TextView(this);
        bundleNote.setTextSize(12);
        root.addView(bundleNote);

        crashNote = new TextView(this);
        crashNote.setTextColor(0xFFFF8080);
        crashNote.setPadding(0, dp(6), 0, dp(6));
        crashNote.setOnClickListener(v -> showLogs());
        root.addView(crashNote);

        TextView mh = new TextView(this);
        mh.setText("Mods (tick the ones to play)");
        mh.setTextSize(15);
        mh.setPadding(0, dp(8), 0, dp(2));
        root.addView(mh);

        ScrollView sv = new ScrollView(this);
        modList = new LinearLayout(this);
        modList.setOrientation(LinearLayout.VERTICAL);
        sv.addView(modList);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout tools = new LinearLayout(this);
        tools.addView(btn("Add mod", v -> addMod()), weight());
        tools.addView(btn("Saves", v -> startActivity(new Intent(this, SavesActivity.class))), weight());
        tools.addView(btn("Restore", v -> restore()), weight());
        tools.addView(btn("Logs", v -> showLogs()), weight());
        root.addView(tools);

        status = new TextView(this);
        status.setText("Ready.");
        root.addView(status);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        root.addView(bar);

        LinearLayout bottom = new LinearLayout(this);
        play = btn("PLAY", v -> play(true));
        play.setTextSize(18);
        original = btn("Play original", v -> play(false));
        bottom.addView(play, new LinearLayout.LayoutParams(0, dp(64), 2f));
        bottom.addView(original, new LinearLayout.LayoutParams(0, dp(64), 1f));
        root.addView(bottom);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    // ---------------------------------------------------------------- list

    private void refresh() {
        List<Integer> bundled = PatchEngine.bundledChapters(this);
        if (bundled.isEmpty()) {
            bundleNote.setText("No PC data.win is bundled in this build. Mods must be made for the Android game files.");
        } else {
            StringBuilder sb = new StringBuilder("PC data.win bundled for chapter");
            sb.append(bundled.size() > 1 ? "s " : " ");
            for (int i = 0; i < bundled.size(); i++) sb.append(i > 0 ? ", " : "").append(bundled.get(i));
            bundleNote.setText(sb.toString());
        }

        File last = newestCrash();
        long seen = Store.prefs(this).getLong("crashSeen", 0);
        if (last != null && last.lastModified() > seen) {
            crashNote.setText("The app crashed last time. Tap here to read the log.");
            crashNote.setVisibility(View.VISIBLE);
        } else {
            crashNote.setVisibility(View.GONE);
        }

        modList.removeAllViews();
        List<Mod> mods = Mod.list(this);
        if (mods.isEmpty()) {
            TextView t = new TextView(this);
            t.setText("No mods yet. Tap Add mod, pick the chapter, then pick the .xdelta file.");
            t.setPadding(0, dp(8), 0, 0);
            modList.addView(t);
        }
        for (final Mod m : mods) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            CheckBox cb = new CheckBox(this);
            int n = m.oggs().size();
            cb.setText(m.name + "   (chapter " + m.chapter + (n > 0 ? ", " + n + " music" : "") + ")");
            cb.setChecked(m.enabled);
            cb.setOnCheckedChangeListener((v, on) -> m.setEnabled(this, on));
            row.addView(cb, weight());
            row.addView(btn("X", v -> new AlertDialog.Builder(this).setMessage("Remove \"" + m.name + "\"?")
                    .setPositiveButton("Remove", (d, w) -> { m.delete(this); refresh(); })
                    .setNegativeButton("Cancel", null).show()));
            modList.addView(row);
        }
    }

    // ---------------------------------------------------------------- add a mod

    private void addMod() {
        String[] items = {"Chapter 1", "Chapter 2", "Chapter 3", "Chapter 4", "Chapter 5"};
        new AlertDialog.Builder(this).setTitle("Which chapter is the mod for?")
                .setItems(items, (d, w) -> {
                    pendingChapter = w + 1;
                    pick(REQ_PATCH, false);
                }).show();
    }

    private void pick(int req, boolean multiple) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, req);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        if (req == REQ_PATCH && data.getData() != null) {
            final Uri u = data.getData();
            status.setText("Adding the mod...");
            new Thread(() -> {
                try {
                    String fileName = Store.displayName(this, u);
                    File tmp = new File(Store.dir(this, "tmp"), "incoming.xdelta");
                    try (java.io.InputStream in = getContentResolver().openInputStream(u)) {
                        if (in == null) throw new java.io.IOException("Cannot open the file");
                        Store.copy(in, tmp);
                    }
                    Vcdiff.requiredSourceSize(tmp); // throws if this is not an xdelta/VCDIFF patch
                    final Mod m = Mod.create(this, fileName, pendingChapter);
                    Store.copy(tmp, m.patch());
                    tmp.delete();
                    CrashLog.note(this, "added mod " + m.name + " for chapter " + m.chapter);
                    ui.post(() -> {
                        pendingMod = m;
                        status.setText("Mod added.");
                        refresh();
                        new AlertDialog.Builder(this).setTitle("Add music?")
                                .setMessage("Optional: add .ogg music files for this mod.")
                                .setPositiveButton("Add music", (d, w) -> pick(REQ_OGG, true))
                                .setNegativeButton("Skip", null).show();
                    });
                } catch (final Exception e) {
                    CrashLog.note(this, "add mod failed: " + e);
                    ui.post(() -> status.setText("That file is not a usable .xdelta patch: " + e.getMessage()));
                }
            }).start();
        } else if (req == REQ_OGG && pendingMod != null) {
            final List<Uri> uris = new ArrayList<>();
            ClipData cd = data.getClipData();
            if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
            else if (data.getData() != null) uris.add(data.getData());
            final Mod m = pendingMod;
            status.setText("Copying music...");
            new Thread(() -> {
                int n = 0;
                for (Uri u : uris) {
                    try {
                        Store.copyFromUri(this, u, m.oggDir());
                        n++;
                    } catch (Exception e) { CrashLog.note(this, "music copy failed: " + e); }
                }
                final int cnt = n;
                ui.post(() -> { status.setText(cnt + " music file(s) added."); refresh(); });
            }).start();
        }
    }

    // ---------------------------------------------------------------- play / restore

    private List<Mod> enabledMods() {
        List<Mod> out = new ArrayList<>();
        for (Mod m : Mod.list(this)) if (m.enabled) out.add(m);
        return out;
    }

    private void setBusy(boolean b) {
        busy = b;
        play.setEnabled(!b);
        original.setEnabled(!b);
    }

    private void play(final boolean withMods) {
        if (busy) return;
        setBusy(true);
        bar.setProgress(0);
        final Context c = getApplicationContext();
        new Thread(() -> {
            try {
                PatchEngine.Log lg = s -> ui.post(() -> status.setText(s));
                if (withMods) {
                    List<Mod> on = enabledMods();
                    CrashLog.note(c, "Play with " + on.size() + " mod(s)");
                    PatchEngine.apply(c, on, (d, t) -> ui.post(() -> bar.setProgress((int) (d * 1000 / Math.max(1, t)))), lg);
                } else {
                    CrashLog.note(c, "Play original");
                    SaveStore.backup(c);
                    PatchEngine.unpatchAll(c);
                }
                ui.post(() -> { setBusy(false); startGame(); });
            } catch (final Throwable t) {
                CrashLog.write(c, "Patch failed", t);
                ui.post(() -> {
                    setBusy(false);
                    bar.setProgress(0);
                    new AlertDialog.Builder(this).setTitle("Could not apply the mods")
                            .setMessage(String.valueOf(t.getMessage()) + "\n\nThe details were saved in Logs. Nothing was changed for the failed chapter.")
                            .setPositiveButton("Play original", (d, w) -> play(false))
                            .setNegativeButton("OK", null).show();
                });
            }
        }).start();
    }

    private void restore() {
        new AlertDialog.Builder(this).setTitle("Restore original")
                .setMessage("Switch every mod off and remove the patched game files. The mods stay in the list and your saves are backed up first.")
                .setPositiveButton("Restore", (d, w) -> new Thread(() -> {
                    try { SaveStore.backup(this); } catch (Exception ignored) { }
                    PatchEngine.restoreOriginal(getApplicationContext());
                    CrashLog.note(this, "Restored original files");
                    ui.post(() -> { status.setText("Original files restored."); refresh(); });
                }).start())
                .setNegativeButton("Cancel", null).show();
    }

    private void startGame() {
        Intent i = new Intent();
        i.setComponent(new ComponentName(getPackageName(), GAME));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        finish();
    }

    // ---------------------------------------------------------------- logs

    private File newestCrash() {
        File[] fs = CrashLog.dir(this).listFiles();
        File best = null;
        if (fs != null) for (File f : fs) if (f.getName().startsWith("crash-") && (best == null || f.lastModified() > best.lastModified())) best = f;
        return best;
    }

    private void showLogs() {
        File[] fs = CrashLog.dir(this).listFiles();
        if (fs == null || fs.length == 0) {
            status.setText("No logs yet.");
            return;
        }
        Arrays.sort(fs, new Comparator<File>() {
            public int compare(File a, File b) { return Long.compare(b.lastModified(), a.lastModified()); }
        });
        final List<File> list = new ArrayList<>();
        Collections.addAll(list, fs);
        String[] names = new String[list.size()];
        for (int i = 0; i < names.length; i++) names[i] = list.get(i).getName();
        Store.prefs(this).edit().putLong("crashSeen", System.currentTimeMillis()).apply();
        crashNote.setVisibility(View.GONE);
        new AlertDialog.Builder(this).setTitle("Logs (newest first)")
                .setItems(names, (d, w) -> viewLog(list.get(w))).show();
    }

    private void viewLog(File f) {
        String text;
        try {
            byte[] b = Store.readBytes(f);
            text = new String(b, 0, Math.min(b.length, 120 * 1024), "UTF-8");
        } catch (Exception e) { text = "Cannot read: " + e; }
        final String shown = text;
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(11);
        t.setTextIsSelectable(true);
        t.setPadding(dp(10), dp(8), dp(10), dp(8));
        ScrollView s = new ScrollView(this);
        s.addView(t);
        new AlertDialog.Builder(this).setTitle(f.getName()).setView(s)
                .setPositiveButton("Share", (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, shown);
                    startActivity(Intent.createChooser(i, "Share log"));
                })
                .setNegativeButton("Close", null).show();
    }

    // ---------------------------------------------------------------- helpers

    private Button btn(String t, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(t);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
