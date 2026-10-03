package com.deltapatch.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/** Save manager: import / export / duplicate / delete / edit. Every change is backed up first. */
public class SavesActivity extends Activity {
    private static final int REQ_IMPORT = 10, REQ_EXPORT = 11;
    private final List<File> files = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private TextView info;
    private File exportSource;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        int p = (int) (10 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);

        TextView t = new TextView(this);
        t.setText("Save files");
        t.setTextSize(20);
        root.addView(t);

        LinearLayout row = new LinearLayout(this);
        Button imp = new Button(this);
        imp.setText("Import");
        imp.setAllCaps(false);
        imp.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("*/*");
            i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
            startActivityForResult(i, REQ_IMPORT);
        });
        Button back = new Button(this);
        back.setText("Back up now");
        back.setAllCaps(false);
        back.setOnClickListener(v -> {
            try {
                File d = SaveStore.backup(this);
                Toast.makeText(this, "Backed up to " + d.getName(), Toast.LENGTH_LONG).show();
            } catch (IOException e) { toast(e); }
        });
        row.addView(imp, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(back, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);

        info = new TextView(this);
        root.addView(info);

        ListView lv = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<String>());
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((a, v, pos, id) -> actions(files.get(pos)));
        root.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        files.clear();
        files.addAll(SaveStore.find(this));
        adapter.clear();
        for (File f : files) adapter.add(f.getName() + "   (" + Store.human(f.length()) + ")");
        info.setText(files.isEmpty()
                ? "No save files found yet. Import one, or start the game and save once.\nImports go to: "
                  + SaveStore.targetDir(this)
                : "Saves folder: " + SaveStore.targetDir(this) + "\nTap a file for actions.");
    }

    private void actions(final File f) {
        new AlertDialog.Builder(this).setTitle(f.getName())
                .setItems(new String[]{"Edit", "Export", "Duplicate to new slot", "Delete"}, (d, which) -> {
                    switch (which) {
                        case 0:
                            Intent i = new Intent(this, SaveEditorActivity.class);
                            i.putExtra("path", f.getAbsolutePath());
                            startActivity(i);
                            break;
                        case 1:
                            exportSource = f;
                            Intent e = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                            e.addCategory(Intent.CATEGORY_OPENABLE);
                            e.setType("application/octet-stream");
                            e.putExtra(Intent.EXTRA_TITLE, f.getName());
                            startActivityForResult(e, REQ_EXPORT);
                            break;
                        case 2:
                            duplicate(f);
                            break;
                        default:
                            confirmDelete(f);
                    }
                }).show();
    }

    private void duplicate(final File f) {
        final EditText et = new EditText(this);
        et.setText(SaveStore.nextFreeName(f.getParentFile(), f.getName()));
        new AlertDialog.Builder(this).setTitle("New file name").setView(et)
                .setPositiveButton("Create", (d, w) -> {
                    String name = et.getText().toString().trim();
                    if (!SaveStore.SAVE.matcher(name).matches()) {
                        Toast.makeText(this, "Name must look like filech3_1", Toast.LENGTH_LONG).show();
                        return;
                    }
                    File dest = new File(f.getParentFile(), name);
                    try {
                        SaveStore.backupOne(this, dest);
                        Store.copy(f, dest);
                        refresh();
                    } catch (IOException ex) { toast(ex); }
                }).setNegativeButton("Cancel", null).show();
    }

    private void confirmDelete(final File f) {
        new AlertDialog.Builder(this).setMessage("Delete " + f.getName() + "? A backup copy is kept.")
                .setPositiveButton("Delete", (d, w) -> {
                    try { SaveStore.backupOne(this, f); f.delete(); refresh(); }
                    catch (IOException ex) { toast(ex); }
                }).setNegativeButton("Cancel", null).show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null) return;
        try {
            if (req == REQ_IMPORT) {
                List<Uri> uris = new ArrayList<>();
                ClipData cd = data.getClipData();
                if (cd != null) for (int i = 0; i < cd.getItemCount(); i++) uris.add(cd.getItemAt(i).getUri());
                else if (data.getData() != null) uris.add(data.getData());
                File dir = SaveStore.targetDir(this);
                SaveStore.backup(this);
                int n = 0;
                for (Uri u : uris) {
                    // ContentResolver streams: works with any provider, no raw paths needed
                    Store.copyFromUri(this, u, dir);
                    n++;
                }
                Toast.makeText(this, n + " file(s) imported", Toast.LENGTH_LONG).show();
                refresh();
            } else if (req == REQ_EXPORT && exportSource != null) {
                Uri u = data.getData();
                try (InputStream in = new FileInputStream(exportSource);
                     OutputStream os = getContentResolver().openOutputStream(u, "wt")) {
                    if (os == null) throw new IOException("Cannot write to destination");
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                Toast.makeText(this, "Exported", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) { toast(e); }
    }

    private void toast(Exception e) {
        Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
    }
}
