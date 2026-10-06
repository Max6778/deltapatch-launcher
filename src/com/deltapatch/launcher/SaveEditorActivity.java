package com.deltapatch.launcher;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;
import java.io.IOException;

/**
 * Line editor for Deltarune save files. Saves are plain text, one value per line;
 * line endings and the trailing space after each value are preserved exactly.
 */
public class SaveEditorActivity extends Activity {
    private File file;
    private SaveStore.Doc doc;
    private ArrayAdapter<String> adapter;
    private boolean dirty;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        file = new File(getIntent().getStringExtra("path"));
        try { doc = SaveStore.load(file); }
        catch (IOException e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); finish(); return; }

        int p = (int) (10 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(p, p, p, p);
        TextView t = new TextView(this);
        t.setText("Editing " + file.getName() + "  (tap a line to change its value)");
        root.addView(t);

        LinearLayout row = new LinearLayout(this);
        Button save = new Button(this);
        save.setText("Save");
        save.setAllCaps(false);
        save.setOnClickListener(v -> save());
        Button go = new Button(this);
        go.setText("Go to line");
        go.setAllCaps(false);
        go.setOnClickListener(v -> goTo());
        row.addView(save, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(go, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row);

        final ListView lv = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new java.util.ArrayList<String>());
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((a, v, pos, id) -> edit(pos));
        root.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        fill();
    }

    private void fill() {
        adapter.clear();
        for (int i = 0; i < doc.lines.size(); i++)
            adapter.add(String.format(java.util.Locale.US, "%4d   %s", i + 1, doc.lines.get(i)));
    }

    private void edit(final int pos) {
        final EditText et = new EditText(this);
        et.setText(doc.lines.get(pos).trim());
        et.setSelection(et.getText().length());
        new AlertDialog.Builder(this).setTitle("Line " + (pos + 1)).setView(et)
                .setPositiveButton("OK", (d, w) -> {
                    doc.lines.set(pos, SaveStore.withSameTail(doc.lines.get(pos), et.getText().toString()));
                    dirty = true;
                    fill();
                }).setNegativeButton("Cancel", null).show();
    }

    private void goTo() {
        final EditText et = new EditText(this);
        et.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        new AlertDialog.Builder(this).setTitle("Go to line").setView(et)
                .setPositiveButton("Go", (d, w) -> {
                    try {
                        int n = Integer.parseInt(et.getText().toString().trim()) - 1;
                        if (n >= 0 && n < doc.lines.size()) edit(n);
                    } catch (NumberFormatException ignored) { }
                }).setNegativeButton("Cancel", null).show();
    }

    private void save() {
        try {
            SaveStore.backupOne(this, file);
            SaveStore.store(file, doc);
            dirty = false;
            Toast.makeText(this, "Saved (backup kept)", Toast.LENGTH_SHORT).show();
        } catch (IOException e) {
            Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onBackPressed() {
        if (!dirty) { super.onBackPressed(); return; }
        new AlertDialog.Builder(this).setMessage("Discard unsaved changes?")
                .setPositiveButton("Discard", (d, w) -> finish())
                .setNegativeButton("Keep editing", null).show();
    }
}
