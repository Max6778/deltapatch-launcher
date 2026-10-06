package com.deltapatch.launcher;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.util.zip.Adler32;

/**
 * Minimal VCDIFF (RFC 3284) decoder, enough for xdelta3 / Delta Patcher patches
 * (default code table, no secondary compression). Pure Java and streaming:
 * the source is read with random access, output is written window by window.
 */
public final class Vcdiff {

    public interface Progress { void onProgress(long done, long total); }

    /** Thrown when a window's Adler-32 does not match: wrong source file / version. */
    public static final class ChecksumMismatch extends IOException {
        public final int window;
        public ChecksumMismatch(int window, String msg) { super(msg); this.window = window; }
    }

    private static final int NOOP = 0, ADD = 1, RUN = 2, COPY = 3;
    private static final int[][] T1 = new int[256][3];
    private static final int[][] T2 = new int[256][3];

    static {
        int n = 0;
        set(n++, RUN, 0, 0, NOOP, 0, 0);
        for (int s = 0; s < 18; s++) set(n++, ADD, s, 0, NOOP, 0, 0);
        for (int m = 0; m < 9; m++) {
            set(n++, COPY, 0, m, NOOP, 0, 0);
            for (int s = 4; s <= 18; s++) set(n++, COPY, s, m, NOOP, 0, 0);
        }
        for (int m = 0; m < 6; m++)
            for (int a = 1; a <= 4; a++)
                for (int c = 4; c <= 6; c++) set(n++, ADD, a, 0, COPY, c, m);
        for (int m = 6; m < 9; m++)
            for (int a = 1; a <= 4; a++) set(n++, ADD, a, 0, COPY, 4, m);
        for (int m = 0; m < 9; m++) set(n++, COPY, 4, m, ADD, 1, 0);
        if (n != 256) throw new IllegalStateException("code table " + n);
    }

    private static void set(int i, int t1, int s1, int m1, int t2, int s2, int m2) {
        T1[i][0] = t1; T1[i][1] = s1; T1[i][2] = m1;
        T2[i][0] = t2; T2[i][1] = s2; T2[i][2] = m2;
    }

    private static final class Rd {
        final byte[] b; int pos;
        Rd(byte[] b) { this.b = b; }
        long v() {
            long n = 0;
            while (true) {
                int x = b[pos++] & 0xff;
                n = (n << 7) | (x & 0x7f);
                if ((x & 0x80) == 0) return n;
            }
        }
    }

    private static byte[] readAll(File f) throws IOException {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            if (r.length() > Integer.MAX_VALUE - 16) throw new IOException("patch too large");
            byte[] b = new byte[(int) r.length()];
            r.readFully(b);
            return b;
        }
    }

    private static void header(Rd r) throws IOException {
        byte[] p = r.b;
        if (p.length < 5 || (p[0] & 0xff) != 0xD6 || (p[1] & 0xff) != 0xC3 || (p[2] & 0xff) != 0xC4)
            throw new IOException("Not a VCDIFF/xdelta file");
        r.pos = 4;
        int hi = p[r.pos++] & 0xff;
        if ((hi & 1) != 0) throw new IOException("Secondary compression not supported (re-create the patch without it)");
        if ((hi & 2) != 0) throw new IOException("Custom code table not supported");
        if ((hi & 4) != 0) { long l = r.v(); r.pos += (int) l; }
    }

    /** Minimum size the source file must have, from all window headers. */
    public static long requiredSourceSize(File patch) throws IOException {
        byte[] p = readAll(patch);
        Rd r = new Rd(p);
        header(r);
        long max = 0;
        while (r.pos < p.length) {
            int wi = p[r.pos++] & 0xff;
            long sl = 0, sp = 0;
            if ((wi & 3) != 0) { sl = r.v(); sp = r.v(); }
            long dl = r.v();
            r.pos += (int) dl;
            if ((wi & 1) != 0) max = Math.max(max, sp + sl);
        }
        return max;
    }

    public static void apply(File patch, File src, File out, boolean ignoreChecksum, Progress prog) throws IOException {
        byte[] p = readAll(patch);
        Rd r = new Rd(p);
        header(r);
        int w = 0;
        try (RandomAccessFile raf = new RandomAccessFile(src, "r");
             OutputStream os = new BufferedOutputStream(new FileOutputStream(out), 1 << 20)) {
            long srcLen = raf.length();
            while (r.pos < p.length) {
                int wi = p[r.pos++] & 0xff;
                long sl = 0, sp = 0;
                if ((wi & 3) != 0) { sl = r.v(); sp = r.v(); }
                if ((wi & 2) != 0) throw new IOException("VCD_TARGET windows not supported");
                if ((wi & 1) != 0 && sp + sl > srcLen)
                    throw new ChecksumMismatch(w, "Source file too small: the patch needs at least "
                            + (sp + sl) + " bytes, the file has " + srcLen
                            + ". It was made for a different file/version.");
                long dl = r.v();
                int end = r.pos + (int) dl;
                int tl = (int) r.v();
                int ind = p[r.pos++] & 0xff;
                if (ind != 0) throw new IOException("Secondary-compressed sections not supported");
                int al = (int) r.v(), il = (int) r.v(), adl = (int) r.v();
                long adler = -1;
                if ((wi & 4) != 0) {
                    adler = ((p[r.pos] & 0xffL) << 24) | ((p[r.pos + 1] & 0xffL) << 16)
                            | ((p[r.pos + 2] & 0xffL) << 8) | (p[r.pos + 3] & 0xffL);
                    r.pos += 4;
                }
                int dp = r.pos, ip = dp + al, ap = ip + il;
                int ie = ap;
                byte[] tgt = new byte[tl];
                int tp = 0;
                long[] near = new long[4];
                long[] same = new long[768];
                int nx = 0;
                Rd ir = new Rd(p), ar = new Rd(p);
                ir.pos = ip; ar.pos = ap;
                while (ir.pos < ie) {
                    int e = p[ir.pos++] & 0xff;
                    for (int k = 0; k < 2; k++) {
                        int[] t = (k == 0 ? T1 : T2)[e];
                        int type = t[0], sz = t[1], mode = t[2];
                        if (type == NOOP) continue;
                        if (type == RUN) {
                            sz = (int) ir.v();
                            byte bv = p[dp++];
                            for (int i = 0; i < sz; i++) tgt[tp++] = bv;
                        } else if (type == ADD) {
                            if (sz == 0) sz = (int) ir.v();
                            System.arraycopy(p, dp, tgt, tp, sz);
                            dp += sz; tp += sz;
                        } else {
                            if (sz == 0) sz = (int) ir.v();
                            long here = sl + tp, a;
                            if (mode == 0) a = ar.v();
                            else if (mode == 1) a = here - ar.v();
                            else if (mode < 6) a = near[mode - 2] + ar.v();
                            else a = same[(mode - 6) * 256 + (p[ar.pos++] & 0xff)];
                            near[nx] = a; nx = (nx + 1) & 3; same[(int) (a % 768)] = a;
                            if (a < sl) {
                                int fromSrc = (int) Math.min(sz, sl - a);
                                raf.seek(sp + a);
                                raf.readFully(tgt, tp, fromSrc);
                                tp += fromSrc;
                                for (int i = fromSrc; i < sz; i++) { tgt[tp] = tgt[tp - fromSrc]; tp++; }
                            } else {
                                int st = (int) (a - sl);
                                for (int i = 0; i < sz; i++) tgt[tp++] = tgt[st + i];
                            }
                        }
                    }
                }
                if (tp != tl) throw new IOException("Corrupt patch (window " + w + ": " + tp + " != " + tl + ")");
                if (adler >= 0 && !ignoreChecksum) {
                    Adler32 ad = new Adler32();
                    ad.update(tgt, 0, tl);
                    if (ad.getValue() != adler)
                        throw new ChecksumMismatch(w, "Checksum mismatch in window " + w
                                + ": this patch was not made for this file/version.");
                }
                os.write(tgt, 0, tl);
                r.pos = end;
                w++;
                if (prog != null) prog.onProgress(r.pos, p.length);
            }
        }
    }
}
