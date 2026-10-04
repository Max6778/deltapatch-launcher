package com.deltapatch.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.Settings;
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
    private static final int REQ_PATCH = 1, REQ_OGG = 2, REQ_SRC = 3, REQ_OGGDIR = 4, REQ_PERM = 20, REQ_TERMUX = 21;
    private String pendingTermux;
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

        LinearLayout top2 = new LinearLayout(this);
        top2.addView(btn("+ OGG folder", v -> pickTree()), weight());
        top2.addView(btn("Sync /sdcard/DeltaPatch", v -> syncFolder()), weight());
        top2.addView(btn("Restore original", v -> restore()), weight());
        root.addView(top2);

        LinearLayout top3 = new LinearLayout(this);
        top3.addView(btn("Make patched file", v -> startActivity(new Intent(this, MakeActivity.class))), weight());
        top3.addView(btn("Advanced (Termux)", v -> converterMenu()), weight());
        root.addView(top3);

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
        askPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (list != null) refresh();
        if (Store.prefs(this).getBoolean("autorun", false) && !busy) {
            Store.prefs(this).edit().putBoolean("autorun", false).apply();
            status.setText("Patched file added. Starting...");
            run(true);
        }
    }

    // ---------------------------------------------------------------- permissions

    /** Asked here, before the game starts, so the game never shows the mic dialog itself (it crashed on it). */
    private void askPermissions() {
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 23) {
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                need.add(Manifest.permission.RECORD_AUDIO);
            if (Build.VERSION.SDK_INT < 30
                    && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                need.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
                need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (!need.isEmpty()) requestPermissions(need.toArray(new String[0]), REQ_PERM);
            else askAllFiles();
        } else {
            askAllFiles();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == REQ_TERMUX) {
            boolean ok = res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED;
            String s = pendingTermux;
            pendingTermux = null;
            if (ok && s != null) runTermux(s);
            else status.setText("Termux permission denied. Allow 'Run commands in Termux environment' for this app in Android settings.");
            return;
        }
        askAllFiles();
    }

    // ---------------------------------------------------------------- Termux converter

    private void converterMenu() {
        new AlertDialog.Builder(this).setTitle("Converter (runs inside Termux)")
                .setItems(new String[]{
                        "0) One-time Termux setup (help)",
                        "1) Install converter",
                        "2) Export files for the converter",
                        "3) Dump code: find the port's edits"}, (d, w) -> {
                    if (w == 0) termuxHelp();
                    else if (w == 1) runTermux("setup.sh");
                    else if (w == 2) exportForConverter();
                    else runTermux("dump.sh");
                }).show();
    }

    private void termuxHelp() {
        final String cmd = TermuxBridge.ENABLE_CMD;
        TextView t = new TextView(this);
        t.setPadding(dp(14), dp(10), dp(14), dp(10));
        t.setTextIsSelectable(true);
        t.setText("1. Install Termux (from F-Droid or GitHub, not the old Play Store build).\n\n"
                + "2. Open Termux once and paste this command, then close and reopen Termux:\n\n" + cmd
                + "\n\n3. Back here, use '1) Install converter'. Android will ask to let this app run commands in Termux: allow it.\n\n"
                + "4. Termux may ask for storage access: allow it.\n\n"
                + "The install downloads about 1 GB and builds UndertaleModTool's command-line tool on the phone; keep the screen on and the phone charging.");
        ScrollView s = new ScrollView(this);
        s.addView(t);
        new AlertDialog.Builder(this).setTitle("Termux setup").setView(s)
                .setPositiveButton("Copy command", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("cmd", cmd));
                    status.setText("Command copied.");
                })
                .setNegativeButton("Close", null).show();
    }

    private void runTermux(final String script) {
        if (!TermuxBridge.installed(this)) { status.setText("Termux is not installed. See 0) One-time Termux setup."); return; }
        if (!hasStorage()) { askPermissions(); askAllFiles(); status.setText("Allow storage access first, then tap again."); return; }
        try { TermuxBridge.writeScripts(); }
        catch (Exception e) { status.setText("Cannot write scripts: " + e.getMessage()); return; }
        if (!TermuxBridge.hasPermission(this)) {
            pendingTermux = script;
            if (Build.VERSION.SDK_INT >= 23) requestPermissions(new String[]{TermuxBridge.PERM}, REQ_TERMUX);
            return;
        }
        try {
            TermuxBridge.run(this, script);
            status.setText("Started " + script + " in Termux. Switch to Termux to watch it.");
        } catch (Exception e) {
            status.setText("Could not start Termux: " + e.getMessage()
                    + " (is allow-external-apps=true set? see 0).");
        }
    }

    /** Copies the files the converter needs to /sdcard/DeltaPatch where Termux can read them. */
    private void exportForConverter() {
        if (!hasStorage()) { askPermissions(); askAllFiles(); status.setText("Allow storage access first."); return; }
        status.setText("Exporting files...");
        new Thread(() -> {
            String msg;
            try {
                File base = new File(Environment.getExternalStorageDirectory(), "DeltaPatch");
                File and = new File(base, "android");
                File pc = new File(base, "pc");
                and.mkdirs(); pc.mkdirs();
                PatchEngine.extractEntry(getApplicationContext(), "chapter3.wad", PatchEngine.GAME_ENTRY,
                        new File(and, "chapter3.droid"));
                File best = null;
                File[] src = Store.dir(this, "sources").listFiles();
                if (src != null) for (File f : src) if (best == null || f.length() > best.length()) best = f;
                if (best != null) Store.copy(best, new File(pc, "data.win"));
                msg = "Exported android/chapter3.droid" + (best != null ? " and pc/data.win (" + best.getName() + ")"
                        : ". No PC file found: add your data.win with + Game file first, then export again.");
            } catch (Exception e) { msg = "Export failed: " + e.getMessage(); }
            final String m = msg;
            ui.post(() -> status.setText(m));
        }).start();
    }

    private boolean hasStorage() {
        if (Build.VERSION.SDK_INT >= 30) return Environment.isExternalStorageManager();
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void askAllFiles() {
        if (Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()
                || Store.prefs(this).getBoolean("askedAllFiles", false)) return;
        Store.prefs(this).edit().putBoolean("askedAllFiles", true).apply();
        new AlertDialog.Builder(this).setTitle("Storage access")
                .setMessage("Allow access to all files so the app can read the /sdcard/DeltaPatch folder "
                        + "(patches, game files, ogg, saves) and use an external save folder.")
                .setPositiveButton("Allow", (d, w) -> {
                    try {
                        startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                    }
                })
                .setNegativeButton("Not now", null).show();
    }

    // ---------------------------------------------------------------- restore / sync / folders

    private void restore() {
        new AlertDialog.Builder(this).setTitle("Restore original")
                .setMessage("Remove all patched chapter files and untick everything. The game will copy its original "
                        + "files from the app again. Your saves are backed up first.")
                .setPositiveButton("Restore", (d, w) -> new Thread(() -> {
                    try { SaveStore.backup(this); } catch (Exception ignored) { }
                    PatchEngine.restoreOriginal(getApplicationContext());
                    ui.post(() -> { status.setText("Original restored."); refresh(); });
                }).start())
                .setNegativeButton("Cancel", null).show();
    }

    private void syncFolder() {
        if (!hasStorage()) {
            Store.prefs(this).edit().putBoolean("askedAllFiles", false).apply();
            askAllFiles();
            askPermissions();
            status.setText("Storage permission needed first.");
            return;
        }
        final File base = new File(Environment.getExternalStorageDirectory(), "DeltaPatch");
        for (String s : new String[]{"patches", "sources", "ogg", "saves"}) new File(base, s).mkdirs();
        status.setText("Syncing " + base + " ...");
        new Thread(() -> {
            int n = 0;
            String err = null;
            try {
                n += syncDir(new File(base, "patches"), Store.dir(this, "patches"));
                n += syncDir(new File(base, "sources"), Store.dir(this, "sources"));
                n += syncDir(new File(base, "ogg"), Store.dir(this, "ogg"));
                File[] sv = new File(base, "saves").listFiles();
                if (sv != null && sv.length > 0) {
                    SaveStore.backup(this);
                    File dir = SaveStore.targetDir(this);
                    for (File f : sv)
                        if (SaveStore.SAVE.matcher(f.getName()).matches()) { Store.copy(f, new File(dir, f.getName())); n++; }
                }
            } catch (Exception e) { err = e.getMessage(); }
            final int count = n;
            final String er = err;
            ui.post(() -> {
                status.setText(count + " file(s) copied from " + base + (er != null ? " - error: " + er : ""));
                refresh();
            });
        }).start();
    }

    private int syncDir(File from, File to) throws java.io.IOException {
        File[] fs = from.listFiles();
        int n = 0;
        if (fs == null) return 0;
        for (File f : fs) {
            if (!f.isFile()) continue;
            File d = new File(to, f.getName());
            if (d.exists() && d.length() == f.length()) continue;
            Store.copy(f, d);
            n++;
        }
        return n;
    }

    private void pickTree() {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_OGGDIR);
    }

    private int copyTreeOgg(Uri tree, String docId, File dest, int depth) throws java.io.IOException {
        Uri kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        int n = 0;
        List<String[]> items = new ArrayList<>();
        try (android.database.Cursor c = getContentResolver().query(kids, new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            while (c != null && c.moveToNext()) items.add(new String[]{c.getString(0), c.getString(1), c.getString(2)});
        }
        for (String[] it : items) {
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(it[2])) {
                if (depth < 2) n += copyTreeOgg(tree, it[0], dest, depth + 1);
            } else if (it[1] != null && it[1].toLowerCase(java.util.Locale.ROOT).endsWith(".ogg")) {
                Uri u = DocumentsContract.buildDocumentUriUsingTree(tree, it[0]);
                try (java.io.InputStream in = getContentResolver().openInputStream(u)) {
                    if (in != null) { Store.copy(in, new File(dest, it[1])); n++; }
                }
            }
        }
        return n;
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
        if (req == REQ_OGGDIR && data.getData() != null) {
            final Uri tree = data.getData();
            status.setText("Copying .ogg files from the folder...");
            new Thread(() -> {
                int n = 0;
                String er = null;
                try {
                    n = copyTreeOgg(tree, DocumentsContract.getTreeDocumentId(tree), Store.dir(this, "ogg"), 0);
                } catch (Exception e) { er = e.getMessage(); }
                final int cnt = n;
                final String err = er;
                ui.post(() -> { status.setText(cnt + " ogg file(s) added" + (err != null ? " - error: " + err : "")); refresh(); });
            }).start();
            return;
        }
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
