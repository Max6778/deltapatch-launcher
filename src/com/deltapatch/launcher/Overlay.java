package com.deltapatch.launcher;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * On-screen keyboard buttons (arrows, Z, X, C) shown only while a chapter that we patched from the PC file is
 * running. The PC game has no touch controls, so the buttons send real key events to the game window.
 * Attached from RunnerActivity.onCreate through a smali hook (see tools/inject.py).
 */
public final class Overlay {
    private Overlay() {}

    public static void attach(final Activity a) {
        try {
            String wad = a.getIntent() == null ? null : a.getIntent().getStringExtra("wad");
            if (wad == null || !new File(Store.dir(a, "patched"), wad).exists()) return;
            int mode = Store.prefs(a).getInt("ctlmode", 0); // 0 auto, 1 launcher buttons, 2 game's own controls
            if (mode == 2) return;
            boolean gml = new File(Store.dir(a, "patched"), wad.replace(".wad", ".gmltouch")).exists();
            if (mode == 0 && gml) return; // auto: the game has its own touch UI
            build(a);
            CrashLog.note(a, "touch buttons shown for " + wad);
        } catch (Throwable t) {
            CrashLog.note(a, "touch buttons failed: " + t);
        }
    }

    private static int fillAlpha = 110, strokeAlpha = 170;

    private static int dp(Activity a, int v) {
        return (int) (v * a.getResources().getDisplayMetrics().density + 0.5f);
    }

    private static void build(final Activity a) {
        FrameLayout root = new FrameLayout(a);
        final List<View> keys = new ArrayList<>();
        int sizeIdx = Store.prefs(a).getInt("ctlsize", 1);
        int alphaIdx = Store.prefs(a).getInt("ctlalpha", 1);
        fillAlpha = alphaIdx == 0 ? 60 : alphaIdx == 1 ? 110 : 170;
        strokeAlpha = alphaIdx == 0 ? 100 : alphaIdx == 1 ? 170 : 230;
        int s = dp(a, sizeIdx == 0 ? 48 : sizeIdx == 1 ? 60 : 72);
        int m = dp(a, 16);
        add(a, root, keys, "^", KeyEvent.KEYCODE_DPAD_UP, 0, s, Gravity.BOTTOM | Gravity.LEFT, m + s, 0, 0, m + 2 * s);
        add(a, root, keys, "<", KeyEvent.KEYCODE_DPAD_LEFT, 0, s, Gravity.BOTTOM | Gravity.LEFT, m, 0, 0, m + s);
        add(a, root, keys, ">", KeyEvent.KEYCODE_DPAD_RIGHT, 0, s, Gravity.BOTTOM | Gravity.LEFT, m + 2 * s, 0, 0, m + s);
        add(a, root, keys, "v", KeyEvent.KEYCODE_DPAD_DOWN, 0, s, Gravity.BOTTOM | Gravity.LEFT, m + s, 0, 0, m);
        add(a, root, keys, "Z", KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_ENTER, s, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, m, m + s);
        add(a, root, keys, "X", KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_SHIFT_LEFT, s, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, m + s + dp(a, 10), m);
        add(a, root, keys, "C", KeyEvent.KEYCODE_C, KeyEvent.KEYCODE_CTRL_LEFT, s, Gravity.BOTTOM | Gravity.RIGHT, 0, 0, m + s + dp(a, 10), m + s + dp(a, 10));

        final TextView toggle = circle(a, "=", dp(a, 40));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(a, 40), dp(a, 40), Gravity.TOP | Gravity.RIGHT);
        lp.setMargins(0, dp(a, 8), dp(a, 8), 0);
        toggle.setOnClickListener(v -> {
            int vis = keys.get(0).getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE;
            for (View k : keys) k.setVisibility(vis);
        });
        root.addView(toggle, lp);
        a.addContentView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    private static TextView circle(Activity a, String label, int size) {
        TextView t = new TextView(a);
        t.setText(label);
        t.setTextColor(Color.WHITE);
        t.setTextSize(18);
        t.setGravity(Gravity.CENTER);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Color.argb(fillAlpha, 40, 40, 40));
        g.setStroke(dp(a, 2), Color.argb(strokeAlpha, 255, 255, 255));
        t.setBackground(g);
        return t;
    }

    private static void add(final Activity a, FrameLayout root, List<View> keys, String label, final int code, final int alt, int size,
                            int gravity, int left, int top, int right, int bottom) {
        TextView t = circle(a, label, size);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size, gravity);
        lp.setMargins(left, top, right, bottom);
        t.setOnTouchListener((v, ev) -> {
            int act = ev.getActionMasked();
            if (act == MotionEvent.ACTION_DOWN) {
                send(a, KeyEvent.ACTION_DOWN, code);
                if (alt != 0) send(a, KeyEvent.ACTION_DOWN, alt);
                v.setAlpha(0.6f);
            } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                send(a, KeyEvent.ACTION_UP, code);
                if (alt != 0) send(a, KeyEvent.ACTION_UP, alt);
                v.setAlpha(1f);
            }
            return true;
        });
        root.addView(t, lp);
        keys.add(t);
    }

    private static void send(Activity a, int action, int code) {
        long now = android.os.SystemClock.uptimeMillis();
        KeyEvent ev = new KeyEvent(now, now, action, code, 0);
        a.getWindow().getDecorView().dispatchKeyEvent(ev);
    }
}
