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
import android.os.StatFs;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shown before the game: patch/ogg list on top, status + launch buttons at the bottom.
 * Everything is built in code so no resources are needed (the whole class lives in an
 * extra dex that is injected into the original APK).
 */
public class LauncherActivity extends Activity {
    private static final int REQ_PATCH = 1, REQ_OGG = 2, REQ_SRC = 3;
    private static final String GAME = "com.hadrian.deltarune.RunnerActivity";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView status;
    private ProgressBar bar;
    private Button launch, normal;
    private CheckBox ignoreCk;
    private EditText oggPrefix;
    private volatile boolean busy;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        int p = dp(10);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("DELTARUNE patch launcher");
        title.setTextSize(20);
        root.addView(title);

        LinearLayout top = new LinearLayout(this);
        top.addView(btn("+ Patch", v -> pick(REQ_PATCH)), weight());
        top.addView(btn("+ OGG", v -> pick(REQ_OGG)), weight());
        top.addView(btn("+ Game file", v -> pick(REQ_SRC)), weight());
        top.addView(btn("Saves", v -> startActivity(new Intent(this, SavesActivity.class))), weight());
        top.addView(btn("Info", v -> showInfo()), weight());
        root.addView(top);

        ScrollView sv = new ScrollView(this);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        ignoreCk = new CheckBox(this);
        ignoreCk.setText("Ignore checksum errors (risky, can break the game)");
        ignoreCk.setChecked(Store.prefs(this).getBoolean("ignoreCk", false));
        root.addView(ignoreCk);

        LinearLayout og = new LinearLayout(this);
        TextView ol = new TextView(this);
        ol.setText("OGG folder inside chapter package: ");
        og.addView(ol);
        oggPrefix = new EditText(this);
        oggPrefix.setSingleLine(true);
        oggPrefix.setText(Store.prefs(this).getString("oggPrefix", "assets/mus/"));
        og.addView(oggPrefix, weight());
        root.addView(og);

        status = new TextView(this);
        status.setText("Ready.");
        root.addView(status);
        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        root.addView(bar);

        LinearLayout bottom = new LinearLayout(this);
        launch = btn("Launch game (apply patches)", v -> run(true));
        normal = btn("Play without patches", v -> run(false));
        bottom.addView(launch, weight());
        bottom.addView(normal, weight());
        root.addView(bottom);

        setContentView(root);
        refresh();
    }

    // ---------------------------------------------------------------- list

    private void refresh() {
        list.removeAllViews();
        addHeader("Patches (.xdelta / .vcdiff) - tick to apply, pick the chapter");
        File[] ps = Store.dir(this, "patches").listFiles();
        if (ps == null || ps.length == 0) addNote("No patches yet. Tap + Patch.");
        else {
            Arrays.sort(ps);
            for (File f : ps) addPatchRow(f);
        }
        addHeader("Game files - a full game.droid/data.win to use as the chapter data (or as the base for patches, e.g. a PC data.win). Tick + pick chapter");
        File[] gs = Store.dir(this, "sources").listFiles();
        if (gs == null || gs.length == 0) addNote("None. Tap + Game file.");
        else {
            Arrays.sort(gs);
            for (File f : gs) addPatchRow(f);
        }
        addHeader("Extra audio (.ogg) - optional");
        File[] os = Store.dir(this, "ogg").listFiles();
        if (os == null || os.length == 0) addNote("No audio files.");
        else {
            Arrays.sort(os);
            for (File f : os) addOggRow(f);
        }
    }

    private void addPatchRow(final File f) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox cb = new CheckBox(this);
        cb.setText(f.getName() + "  (" + Store.human(f.length()) + ")");
        cb.setChecked(Store.prefs(this).getBoolean("on." + f.getName(), false));
        cb.setOnCheckedChangeListener((v, on) -> Store.prefs(this).edit().putBoolean("on." + f.getName(), on).apply());
        row.addView(cb, weight());
        Spinner sp = new Spinner(this);
        List<String> items = new ArrayList<>();
        for (int i = 0; i <= 5; i++) items.add(i == 0 ? "Ch 0" : "Ch " + i);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, items));
        sp.setSelection(Store.prefs(this).getInt("ch." + f.getName(), 3));
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            public void onItemSelected(AdapterView<?> a, View v, int pos, long id) {
                Store.prefs(LauncherActivity.this).edit().putInt("ch." + f.getName(), pos).apply();
            }
            public void onNothingSelected(AdapterView<?> a) { }
        });
        row.addView(sp);
        row.addView(btn("X", v -> delete(f)));
        list.addView(row);
    }

    private void addOggRow(final File f) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        CheckBox cb = new CheckBox(this);
        cb.setText(f.getName() + "  (" + Store.human(f.length()) + ")");
        cb.setChecked(Store.prefs(this).getBoolean("on." + f.getName(), false));
        cb.setOnCheckedChangeListener((v, on) -> Store.prefs(this).edit().putBoolean("on." + f.getName(), on).apply());
        row.addView(cb, weight());
        row.addView(btn("X", v -> delete(f)));
        list.addView(row);
    }

    private void delete(final File f) {
        new AlertDialog.Builder(this).setMessage("Delete " + f.getName() + "?")
                .setPositiveButton("Delete", (d, w) -> { f.delete(); refresh(); })
                .setNegativeButton("Cancel", null).show();
    }

    private void addHeader(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setPadding(0, dp(10), 0, dp(2));
        list.addView(t);
    }

    private void addNote(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        list.addView(t);
    }

    // ---------------------------------------------------------------- add files

    private void pick(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, req);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        final List<Uri> uris = new ArrayList<>();
        ClipData cd = data.getClipData();
        if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
        else if (data.getData() != null) uris.add(data.getData());
        final File dest = Store.dir(this, req == REQ_PATCH ? "patches" : req == REQ_SRC ? "sources" : "ogg");
        status.setText("Copying " + uris.size() + " file(s)...");
        new Thread(() -> {
            int ok = 0;
            String err = null;
            for (Uri u : uris) {
                try { Store.copyFromUri(this, u, dest); ok++; }
                catch (Exception e) { err = e.getMessage(); }
            }
            final int n = ok;
            final String er = err;
            ui.post(() -> {
                status.setText(n + " file(s) added." + (er != null ? " Error: " + er : ""));
                refresh();
            });
        }).start();
    }

    // ---------------------------------------------------------------- run

    private void run(final boolean patch) {
        if (busy) return;
        busy = true;
        launch.setEnabled(false);
        normal.setEnabled(false);
        Store.prefs(this).edit().putBoolean("ignoreCk", ignoreCk.isChecked())
                .putString("oggPrefix", oggPrefix.getText().toString()).apply();
        final boolean ign = ignoreCk.isChecked();
        final String prefix = oggPrefix.getText().toString();
        final Context c = getApplicationContext();
        new Thread(() -> {
            try {
                PatchEngine.Log lg = s -> ui.post(() -> status.setText(s));
                if (patch) {
                    PatchEngine.applyAll(c, ign, prefix,
                            (done, total) -> ui.post(() -> bar.setProgress((int) (done * 1000 / Math.max(1, total)))), lg);
                } else {
                    SaveStore.backup(c);
                    PatchEngine.unpatchAll(c, lg);
                }
                ui.post(() -> { busy = false; startGame(); });
            } catch (final Throwable t) {
                ui.post(() -> {
                    busy = false;
                    launch.setEnabled(true);
                    normal.setEnabled(true);
                    bar.setProgress(0);
                    new AlertDialog.Builder(this).setTitle("Patch failed")
                            .setMessage(String.valueOf(t.getMessage())
                                    + "\n\nNothing was changed for the failed chapter. You can still start the game unpatched.")
                            .setPositiveButton("Launch unpatched anyway", (d, w) -> run(false))
                            .setNegativeButton("Stay here", null).show();
                });
            }
        }).start();
    }

    private void startGame() {
        Intent i = new Intent();
        i.setComponent(new ComponentName(getPackageName(), GAME));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
        finish();
    }

    // ---------------------------------------------------------------- info

    private void showInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("Free space: ").append(Store.human(new StatFs(getFilesDir().getAbsolutePath()).getAvailableBytes())).append("\n\n");
        for (int ch = 0; ch <= 5; ch++) {
            File w = Store.wadFile(this, ch);
            sb.append("chapter").append(ch).append(".wad: ")
                    .append(w.exists() ? Store.human(w.length()) : "not extracted yet")
                    .append(PatchEngine.isPatched(this, ch) ? "  [PATCHED]" : "").append("\n");
        }
        sb.append("\nSave files found:\n");
        for (File f : SaveStore.find(this)) sb.append("  ").append(f.getAbsolutePath()).append("\n");
        File log = new File(Store.root(this), "hook.log");
        sb.append("\nGame extraction log (last lines):\n");
        try {
            List<String> ls = Arrays.asList(new String(Store.readBytes(log), "UTF-8").split("\n"));
            for (int i = Math.max(0, ls.size() - 12); i < ls.size(); i++) sb.append(ls.get(i)).append("\n");
        } catch (Exception e) { sb.append("(empty - game hasn't extracted anything yet)\n"); }
        TextView t = new TextView(this);
        t.setText(sb.toString());
        t.setPadding(dp(12), dp(8), dp(12), dp(8));
        t.setTextIsSelectable(true);
        ScrollView s = new ScrollView(this);
        s.addView(t);
        new AlertDialog.Builder(this).setTitle("Info").setView(s).setPositiveButton("OK", null).show();
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
