package com.deltapatch.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Step-by-step wizard that makes the patched game file (PC data.win + .xdelta patch) and hands it to the launcher.
 * Two routes: Termux (on the phone) or GitHub (cloud job).
 */
public class MakeActivity extends Activity {
    private static final String TAG_RELEASE = "deltapatch-input";
    private static final String IN_PC = "data.win", IN_PATCH = "patch.xdelta";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private LinearLayout stepsBox;
    private TextView info;
    private ProgressBar bar;
    private Spinner spPc, spPatch, spCh;
    private final List<File> pcFiles = new ArrayList<>();
    private final List<File> patchFiles = new ArrayList<>();
    private boolean destroyed;
    private int method = -1; // 0 termux, 1 github
    private EditText repoEt, tokEt;
    private TextView termuxMark, termuxRunMark, prepMark, ghMark3, ghMark4, ghMark5;
    private TextView termuxRunText;
    private volatile boolean busy;

    // ------------------------------------------------------------------ UI scaffolding

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int p = dp(10);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);

        TextView t = new TextView(this);
        t.setText("Make the patched game file");
        t.setTextSize(20);
        root.addView(t);

        TextView h1 = label("What to patch (add files in the launcher with + Game file / + Patch first):");
        root.addView(h1);
        spPc = new Spinner(this);
        spPatch = new Spinner(this);
        spCh = new Spinner(this);
        root.addView(label("PC data.win (the original, unpatched):"));
        root.addView(spPc);
        root.addView(label("Patch (.xdelta):"));
        root.addView(spPatch);
        root.addView(label("Chapter:"));
        List<String> chs = new ArrayList<>();
        for (int i = 0; i <= 5; i++) chs.add("Chapter " + i);
        spCh.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, chs));
        spCh.setSelection(3);
        root.addView(spCh);

        LinearLayout choose = new LinearLayout(this);
        choose.addView(btn("Way 1: Termux (on this phone)", v -> pickMethod(0)), weight());
        choose.addView(btn("Way 2: GitHub (cloud)", v -> pickMethod(1)), weight());
        root.addView(choose);

        bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        root.addView(bar);
        info = new TextView(this);
        info.setText("Pick a way above.");
        root.addView(info);

        ScrollView sv = new ScrollView(this);
        stepsBox = new LinearLayout(this);
        stepsBox.setOrientation(LinearLayout.VERTICAL);
        sv.addView(stepsBox);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        reloadFiles();
    }

    @Override
    protected void onResume() {
        super.onResume();
        reloadFiles();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        super.onDestroy();
    }

    private void reloadFiles() {
        pcFiles.clear();
        patchFiles.clear();
        File[] a = Store.dir(this, "sources").listFiles();
        if (a != null) { Arrays.sort(a); for (File f : a) if (!f.getName().startsWith("patched_")) pcFiles.add(f); }
        File[] b = Store.dir(this, "patches").listFiles();
        if (b != null) { Arrays.sort(b); patchFiles.addAll(Arrays.asList(b)); }
        spPc.setAdapter(adapterFor(pcFiles));
        spPatch.setAdapter(adapterFor(patchFiles));
    }

    private ArrayAdapter<String> adapterFor(List<File> fs) {
        List<String> n = new ArrayList<>();
        for (File f : fs) n.add(f.getName() + "  (" + Store.human(f.length()) + ")");
        if (n.isEmpty()) n.add("(none - add it in the launcher)");
        return new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, n);
    }

    private File selPc() {
        int i = spPc.getSelectedItemPosition();
        return i >= 0 && i < pcFiles.size() ? pcFiles.get(i) : null;
    }

    private File selPatch() {
        int i = spPatch.getSelectedItemPosition();
        return i >= 0 && i < patchFiles.size() ? patchFiles.get(i) : null;
    }

    private int selCh() { return spCh.getSelectedItemPosition(); }

    private void pickMethod(int m) {
        method = m;
        stepsBox.removeAllViews();
        bar.setProgress(0);
        if (m == 0) buildTermux(); else buildGithub();
    }

    // ------------------------------------------------------------------ step helpers

    private TextView addStep(String title, String desc, String btnText, Runnable action) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(8));
        TextView head = new TextView(this);
        head.setText("[ ]  " + title);
        head.setTextSize(16);
        box.addView(head);
        if (desc != null) {
            TextView d = new TextView(this);
            d.setText(desc);
            box.addView(d);
        }
        if (btnText != null && action != null) {
            final Runnable act = action;
            box.addView(btn(btnText, v -> act.run()));
        }
        stepsBox.addView(box);
        return head;
    }

    private void mark(final TextView head, final boolean ok) {
        if (head == null) return;
        ui.post(() -> {
            String s = head.getText().toString();
            int i = s.indexOf("  ");
            String rest = i >= 0 ? s.substring(i + 2) : s;
            head.setText((ok ? "[x]  " : "[ ]  ") + rest);
        });
    }

    private void say(final String s) { ui.post(() -> info.setText(s)); }

    private void progress(final long done, final long total) {
        ui.post(() -> bar.setProgress((int) (done * 1000 / Math.max(1, total))));
    }

    // ------------------------------------------------------------------ way 1: Termux

    private void buildTermux() {
        say("Way 1 runs xdelta3 inside Termux on this phone. Follow the steps in order.");
        termuxMark = addStep("Install Termux",
                "Get Termux from F-Droid or GitHub (not the old Play Store version).",
                "Open download page", () -> open("https://f-droid.org/packages/com.termux/"));
        mark(termuxMark, TermuxBridge.installed(this));

        final String cmd = TermuxBridge.ENABLE_CMD;
        addStep("Let this app run commands in Termux (once)",
                "Open Termux, paste this command, then close and reopen Termux:\n" + cmd,
                "Copy the command", () -> {
                    ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(ClipData.newPlainText("cmd", cmd));
                    say("Command copied. Paste it in Termux.");
                });

        prepMark = addStep("Copy the files for Termux",
                "Copies the chosen PC data.win and patch to /sdcard/DeltaPatch (needs all-files access).",
                "Copy files", this::termuxPrepare);

        termuxRunMark = addStep("Make the patched file in Termux",
                "Termux opens, installs xdelta3 if needed and applies the patch. Come back here when it says Done.",
                "Run in Termux", this::termuxRun);

        addStep("Use the result and launch",
                "Adds the patched file as this chapter's game file (ticked) and starts the game through the launcher.",
                "Use result and launch", () -> {
                    File out = new File(Environment.getExternalStorageDirectory(),
                            "DeltaPatch/out/chapter" + selCh() + ".droid");
                    if (!out.isFile()) { say("The patched file isn't there yet (out/chapter" + selCh() + ".droid)."); return; }
                    useResult(out, true);
                });
        mark(prepMark, new File(Environment.getExternalStorageDirectory(), "DeltaPatch/pc/data.win").isFile()
                && new File(Environment.getExternalStorageDirectory(), "DeltaPatch/pc/patch.xdelta").isFile());
    }

    private boolean needStorage() {
        boolean ok = Build.VERSION.SDK_INT >= 30 ? Environment.isExternalStorageManager()
                : (Build.VERSION.SDK_INT < 23 || checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED);
        if (!ok) say("Allow storage access in the launcher first (it asks on start), then retry.");
        return !ok;
    }

    private void termuxPrepare() {
        final File pc = selPc(), patch = selPatch();
        if (pc == null || patch == null) { say("Pick a PC data.win and a patch above (add them in the launcher first)."); return; }
        if (needStorage() || busy) return;
        busy = true;
        say("Copying files...");
        new Thread(() -> {
            try {
                File base = new File(Environment.getExternalStorageDirectory(), "DeltaPatch");
                File dir = new File(base, "pc");
                dir.mkdirs();
                new File(base, "out").mkdirs();
                new File(base, "out/result.txt").delete();
                Store.copy(pc, new File(dir, "data.win"));
                Store.copy(patch, new File(dir, "patch.xdelta"));
                mark(prepMark, true);
                say("Files copied.");
            } catch (Exception e) {
                say("Copy failed: " + e.getMessage());
            } finally { busy = false; }
        }).start();
    }

    private void termuxRun() {
        if (!TermuxBridge.installed(this)) { say("Termux is not installed (step 1)."); return; }
        if (needStorage()) return;
        try {
            TermuxBridge.writeScripts();
            TermuxBridge.writeJob(selCh());
        } catch (Exception e) { say("Cannot write scripts: " + e.getMessage()); return; }
        if (!TermuxBridge.hasPermission(this)) {
            if (Build.VERSION.SDK_INT >= 23) requestPermissions(new String[]{TermuxBridge.PERM}, 31);
            say("Allow 'Run commands in Termux', then tap Run again.");
            return;
        }
        try {
            new File(Environment.getExternalStorageDirectory(), "DeltaPatch/out/result.txt").delete();
            TermuxBridge.run(this, "make.sh");
            say("Started in Termux. Waiting for it to finish...");
            pollTermux();
        } catch (Exception e) {
            say("Could not start Termux: " + e.getMessage() + " (is allow-external-apps=true set? see step 2)");
        }
    }

    private void pollTermux() {
        final File res = new File(Environment.getExternalStorageDirectory(), "DeltaPatch/out/result.txt");
        ui.postDelayed(new Runnable() {
            int n = 0;
            public void run() {
                if (destroyed) return;
                String s = null;
                try { if (res.isFile()) s = new String(Store.readBytes(res), "UTF-8").trim(); } catch (Exception ignored) { }
                if (s != null && s.startsWith("OK")) {
                    mark(termuxRunMark, true);
                    say("Patched file is ready. Use the last step.");
                    return;
                }
                if (s != null && s.startsWith("ERROR")) { say(s); return; }
                if (++n < 900) ui.postDelayed(this, 2000);
            }
        }, 3000);
    }

    // ------------------------------------------------------------------ way 2: GitHub

    private void buildGithub() {
        say("Way 2 uses a PRIVATE GitHub repo that has .github/workflows/cloud-patch.yml. Follow the steps in order.");
        SharedPreferences sp = Store.prefs(this);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        repoEt = new EditText(this);
        repoEt.setSingleLine(true);
        repoEt.setHint("owner/repo (must be PRIVATE)");
        repoEt.setText(sp.getString("ghRepo", ""));
        form.addView(repoEt);
        tokEt = new EditText(this);
        tokEt.setSingleLine(true);
        tokEt.setHint("GitHub token");
        tokEt.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        tokEt.setText(sp.getString("ghToken", ""));
        form.addView(tokEt);
        stepsBox.addView(form);

        final TextView s1 = addStep("Private repo + token",
                "Use a PRIVATE repo: your game file gets uploaded there, and it must never be public. "
                        + "Token: fine-grained, only that repo, permissions Contents: Read and write, Actions: Read and write. "
                        + "The token stays on this phone.",
                "Check repo and token", null);
        LinearLayout row = new LinearLayout(this);
        row.addView(btn("Create token", v -> open("https://github.com/settings/personal-access-tokens/new")), weight());
        row.addView(btn("Check repo + token", v -> ghCheck(s1)), weight());
        stepsBox.addView(row);

        ghMark3 = addStep("Upload the files",
                "Uploads the PC data.win and the patch to a release named " + TAG_RELEASE + " in your private repo.",
                "Upload", this::ghUpload);
        ghMark4 = addStep("Run the cloud job",
                "Starts the cloud-patch workflow and waits for it (a few minutes).",
                "Start cloud job", this::ghRun);
        ghMark5 = addStep("Download the result and launch",
                "Downloads patched_chapterN_windows.droid, adds it as the game file (ticked) and starts the game.",
                "Download and launch", this::ghDownload);
    }

    private GitHub gh() throws Exception {
        String repo = repoEt.getText().toString().trim();
        String tok = tokEt.getText().toString().trim();
        Store.prefs(this).edit().putString("ghRepo", repo).putString("ghToken", tok).apply();
        if (tok.isEmpty()) throw new Exception("Paste your GitHub token first.");
        return new GitHub(tok, repo);
    }

    private void ghCheck(final TextView mk) {
        if (busy) return;
        busy = true;
        say("Checking...");
        new Thread(() -> {
            try {
                GitHub g = gh();
                if (g.isPublic()) {
                    say("STOP: this repo is PUBLIC. Do not upload game files to a public repo. Make it private "
                            + "(repo Settings > Danger zone) or use another private repo.");
                    mark(mk, false);
                } else {
                    String branch = g.checkAccess();
                    say("OK. Repo reachable with this token (default branch: " + branch + ").");
                    mark(mk, true);
                }
            } catch (Exception e) { say("Check failed: " + e.getMessage()); mark(mk, false); }
            finally { busy = false; }
        }).start();
    }

    private void ghUpload() {
        final File pc = selPc(), patch = selPatch();
        if (pc == null || patch == null) { say("Pick a PC data.win and a patch above."); return; }
        if (busy) return;
        busy = true;
        say("Uploading (" + Store.human(pc.length()) + ")...");
        new Thread(() -> {
            try {
                GitHub g = gh();
                if (g.isPublic()) throw new Exception("The repo is PUBLIC. Refusing to upload game files.");
                g.checkAccess();
                JSONObject rel = g.ensureRelease(TAG_RELEASE);
                g.uploadAsset(rel, IN_PATCH, patch, null);
                rel = g.release(TAG_RELEASE);
                g.uploadAsset(rel, IN_PC, pc, (d, t) -> {
                    progress(d, t);
                    say("Uploading data.win: " + Store.human(d) + " / " + Store.human(t));
                });
                mark(ghMark3, true);
                say("Uploaded. Next: start the cloud job.");
            } catch (Exception e) { say("Upload failed: " + e.getMessage()); }
            finally { busy = false; }
        }).start();
    }

    private void ghRun() {
        if (busy) return;
        busy = true;
        final int ch = selCh();
        say("Starting the cloud job...");
        new Thread(() -> {
            try {
                GitHub g = gh();
                String branch = g.checkAccess();
                long start = System.currentTimeMillis();
                JSONObject in = new JSONObject();
                in.put("release_tag", TAG_RELEASE);
                in.put("source_mode", "release");
                in.put("patch_name", IN_PATCH);
                in.put("source_name", IN_PC);
                in.put("steam_chapter", "chapter" + ch + "_windows");
                g.dispatch(branch, in);
                SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                JSONObject run = null;
                for (int i = 0; i < 400 && !destroyed; i++) {
                    Thread.sleep(6000);
                    JSONObject r = g.latestRun();
                    if (r == null) { say("Waiting for the job to appear..."); continue; }
                    Date created = f.parse(r.optString("created_at"));
                    if (created == null || created.getTime() < start - 90000) { say("Waiting for the job to appear..."); continue; }
                    run = r;
                    String st = r.optString("status");
                    say("Cloud job: " + st + " (" + r.optString("html_url") + ")");
                    if ("completed".equals(st)) break;
                }
                if (run == null) throw new Exception("The job never appeared. Is cloud-patch.yml in the repo's default branch?");
                if ("success".equals(run.optString("conclusion"))) {
                    mark(ghMark4, true);
                    say("Cloud job finished OK. Next: download the result.");
                } else {
                    say("Cloud job ended with: " + run.optString("conclusion") + ". Open " + run.optString("html_url")
                            + " to see why (often: wrong data.win version).");
                }
            } catch (Exception e) { say("Cloud job failed: " + e.getMessage()); }
            finally { busy = false; }
        }).start();
    }

    private void ghDownload() {
        if (busy) return;
        busy = true;
        final int ch = selCh();
        say("Downloading...");
        new Thread(() -> {
            try {
                GitHub g = gh();
                JSONObject rel = g.release(TAG_RELEASE);
                if (rel == null) throw new Exception("No " + TAG_RELEASE + " release found.");
                JSONArray assets = rel.optJSONArray("assets");
                JSONObject found = null;
                String want = "patched_chapter" + ch + "_windows.droid";
                for (int i = 0; assets != null && i < assets.length(); i++)
                    if (want.equals(assets.getJSONObject(i).optString("name"))) found = assets.getJSONObject(i);
                if (found == null) throw new Exception(want + " is not in the release yet. Did the cloud job succeed?");
                File tmp = new File(Store.dir(this, "tmp"), "download.droid");
                g.download(found, tmp, (d, t) -> {
                    progress(d, t);
                    say("Downloading: " + Store.human(d) + " / " + Store.human(t));
                });
                mark(ghMark5, true);
                final File got = tmp;
                ui.post(() -> useResult(got, true));
            } catch (Exception e) { say("Download failed: " + e.getMessage()); }
            finally { busy = false; }
        }).start();
    }

    // ------------------------------------------------------------------ hand over to the launcher

    private void useResult(final File produced, final boolean launch) {
        final int ch = selCh();
        say("Adding the patched file...");
        new Thread(() -> {
            try {
                File dest = new File(Store.dir(this, "sources"), "patched_chapter" + ch + ".droid");
                if (!produced.getAbsolutePath().equals(dest.getAbsolutePath())) Store.copy(produced, dest);
                if (produced.getName().equals("download.droid")) produced.delete();
                SharedPreferences sp = Store.prefs(this);
                SharedPreferences.Editor ed = sp.edit();
                File[] ss = Store.dir(this, "sources").listFiles();
                if (ss != null) for (File f : ss)
                    if (sp.getInt("ch." + f.getName(), 3) == ch) ed.putBoolean("on." + f.getName(), false);
                File[] ps = Store.dir(this, "patches").listFiles();
                if (ps != null) for (File f : ps)
                    if (sp.getInt("ch." + f.getName(), 3) == ch) ed.putBoolean("on." + f.getName(), false);
                ed.putBoolean("on." + dest.getName(), true).putInt("ch." + dest.getName(), ch);
                if (launch) ed.putBoolean("autorun", true);
                ed.apply();
                ui.post(() -> { if (!destroyed) finish(); });
            } catch (Exception e) { say("Could not add the file: " + e.getMessage()); }
        }).start();
    }

    // ------------------------------------------------------------------ small helpers

    private void open(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (Exception e) { say("Cannot open " + url); }
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, dp(6), 0, 0);
        return t;
    }

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

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }
}
