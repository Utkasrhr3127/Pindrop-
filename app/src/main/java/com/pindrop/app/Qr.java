package com.pindrop.app;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Tiny QR code encoder: byte mode, error correction level M, versions 1 to 10. No dependencies. */
public final class Qr {
    private static final int[] ECC = {0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] BLK = {0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};

    private Qr() {}

    private static int rawModules(int ver) {
        int r = (16 * ver + 128) * ver + 64;
        if (ver >= 2) {
            int n = ver / 7 + 2;
            r -= (25 * n - 10) * n - 55;
            if (ver >= 7) r -= 36;
        }
        return r;
    }

    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z;
    }

    private static int[] rsDivisor(int deg) {
        int[] res = new int[deg];
        res[deg - 1] = 1;
        int root = 1;
        for (int i = 0; i < deg; i++) {
            for (int j = 0; j < deg; j++) {
                res[j] = mul(res[j], root);
                if (j + 1 < deg) res[j] ^= res[j + 1];
            }
            root = mul(root, 2);
        }
        return res;
    }

    private static int[] rsRemainder(int[] data, int[] div) {
        int[] res = new int[div.length];
        for (int b : data) {
            int f = b ^ res[0];
            System.arraycopy(res, 1, res, 0, res.length - 1);
            res[res.length - 1] = 0;
            for (int i = 0; i < div.length; i++) res[i] ^= mul(div[i], f);
        }
        return res;
    }

    private static int put(boolean[] bits, int pos, int value, int count) {
        for (int i = count - 1; i >= 0; i--) bits[pos++] = ((value >>> i) & 1) != 0;
        return pos;
    }

    /** Returns the QR modules as [row][column], true = dark. Throws if the text is too long. */
    public static boolean[][] encode(String text) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int ver = 0, cap = 0;
        for (int v = 1; v <= 10; v++) {
            int c = rawModules(v) / 8 - ECC[v] * BLK[v];
            int need = 4 + (v < 10 ? 8 : 16) + 8 * data.length;
            if (need <= c * 8) { ver = v; cap = c; break; }
        }
        if (ver == 0) throw new IllegalArgumentException("Text is too long for a QR code");

        boolean[] bits = new boolean[cap * 8];
        int n = put(bits, 0, 4, 4);
        n = put(bits, n, data.length, ver < 10 ? 8 : 16);
        for (byte b : data) n = put(bits, n, b & 0xFF, 8);
        n += Math.min(4, cap * 8 - n);
        while (n % 8 != 0) n++;
        int[] cw = new int[cap];
        for (int i = 0; i < n; i++) if (bits[i]) cw[i >> 3] |= 0x80 >> (i & 7);
        int pad = 0xEC;
        for (int i = n / 8; i < cap; i++) { cw[i] = pad; pad ^= 0xEC ^ 0x11; }

        int nb = BLK[ver], eb = ECC[ver], rawCw = rawModules(ver) / 8;
        int shortBlocks = nb - rawCw % nb, shortLen = rawCw / nb - eb;
        int[] div = rsDivisor(eb);
        int[][] dBlocks = new int[nb][], eBlocks = new int[nb][];
        int k = 0;
        for (int i = 0; i < nb; i++) {
            int dl = shortLen + (i < shortBlocks ? 0 : 1);
            dBlocks[i] = Arrays.copyOfRange(cw, k, k + dl);
            k += dl;
            eBlocks[i] = rsRemainder(dBlocks[i], div);
        }
        int[] all = new int[rawCw];
        int o = 0;
        for (int i = 0; i < shortLen + 1; i++)
            for (int j = 0; j < nb; j++)
                if (i < dBlocks[j].length) all[o++] = dBlocks[j][i];
        for (int i = 0; i < eb; i++)
            for (int j = 0; j < nb; j++) all[o++] = eBlocks[j][i];

        int size = ver * 4 + 17;
        boolean[][] mod = new boolean[size][size];
        boolean[][] fn = new boolean[size][size];
        drawFunctionPatterns(mod, fn, ver, size);
        placeCodewords(mod, fn, all, size);

        boolean[][] best = null;
        int bestPenalty = Integer.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            boolean[][] t = new boolean[size][];
            for (int y = 0; y < size; y++) t[y] = mod[y].clone();
            for (int y = 0; y < size; y++)
                for (int x = 0; x < size; x++)
                    if (!fn[y][x] && maskBit(m, x, y)) t[y][x] = !t[y][x];
            drawFormat(t, null, size, m);
            int p = penalty(t, size);
            if (p < bestPenalty) { bestPenalty = p; best = t; }
        }
        return best;
    }

    private static void set(boolean[][] mod, boolean[][] fn, int x, int y, boolean v) {
        mod[y][x] = v;
        if (fn != null) fn[y][x] = true;
    }

    private static void drawFunctionPatterns(boolean[][] mod, boolean[][] fn, int ver, int size) {
        for (int i = 0; i < size; i++) {
            set(mod, fn, 6, i, i % 2 == 0);
            set(mod, fn, i, 6, i % 2 == 0);
        }
        finder(mod, fn, 3, 3, size);
        finder(mod, fn, size - 4, 3, size);
        finder(mod, fn, 3, size - 4, size);
        if (ver > 1) {
            int n = ver / 7 + 2;
            int step = ver == 32 ? 26 : (ver * 4 + n * 2 + 1) / (n * 2 - 2) * 2;
            int[] pos = new int[n];
            pos[0] = 6;
            for (int i = 1; i < n; i++) pos[i] = size - 7 - (n - 1 - i) * step;
            for (int i = 0; i < n; i++)
                for (int j = 0; j < n; j++) {
                    if ((i == 0 && j == 0) || (i == 0 && j == n - 1) || (i == n - 1 && j == 0)) continue;
                    for (int dy = -2; dy <= 2; dy++)
                        for (int dx = -2; dx <= 2; dx++)
                            set(mod, fn, pos[i] + dx, pos[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
                }
        }
        drawFormat(mod, fn, size, 0);
        if (ver >= 7) {
            int rem = ver;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = (ver << 12) | rem;
            for (int i = 0; i < 18; i++) {
                boolean c = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, b = i / 3;
                set(mod, fn, a, b, c);
                set(mod, fn, b, a, c);
            }
        }
    }

    private static void finder(boolean[][] mod, boolean[][] fn, int x, int y, int size) {
        for (int dy = -4; dy <= 4; dy++)
            for (int dx = -4; dx <= 4; dx++) {
                int xx = x + dx, yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) {
                    int d = Math.max(Math.abs(dx), Math.abs(dy));
                    set(mod, fn, xx, yy, d != 2 && d != 4);
                }
            }
    }

    private static void drawFormat(boolean[][] mod, boolean[][] fn, int size, int mask) {
        int data = mask; // error correction level M is 0
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = ((data << 10) | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(mod, fn, 8, i, bit(bits, i));
        set(mod, fn, 8, 7, bit(bits, 6));
        set(mod, fn, 8, 8, bit(bits, 7));
        set(mod, fn, 7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(mod, fn, 14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(mod, fn, size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(mod, fn, 8, size - 15 + i, bit(bits, i));
        set(mod, fn, 8, size - 8, true);
    }

    private static boolean bit(int v, int i) { return ((v >>> i) & 1) != 0; }

    private static void placeCodewords(boolean[][] mod, boolean[][] fn, int[] cw, int size) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++)
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!fn[y][x] && i < cw.length * 8) {
                        mod[y][x] = ((cw[i >>> 3] >>> (7 - (i & 7))) & 1) != 0;
                        i++;
                    }
                }
        }
    }

    private static boolean maskBit(int m, int x, int y) {
        switch (m) {
            case 0: return (x + y) % 2 == 0;
            case 1: return y % 2 == 0;
            case 2: return x % 3 == 0;
            case 3: return (x + y) % 3 == 0;
            case 4: return (x / 3 + y / 2) % 2 == 0;
            case 5: return x * y % 2 + x * y % 3 == 0;
            case 6: return (x * y % 2 + x * y % 3) % 2 == 0;
            default: return ((x + y) % 2 + x * y % 3) % 2 == 0;
        }
    }

    private static int count(String s, String p) {
        int n = 0, i = s.indexOf(p);
        while (i >= 0) { n++; i = s.indexOf(p, i + p.length()); }
        return n;
    }

    private static int penalty(boolean[][] mod, int size) {
        int p = 0;
        for (int dir = 0; dir < 2; dir++) {
            for (int a = 0; a < size; a++) {
                StringBuilder sb = new StringBuilder();
                int run = 1;
                for (int b = 0; b < size; b++) {
                    boolean v = dir == 0 ? mod[a][b] : mod[b][a];
                    sb.append(v ? '1' : '0');
                    if (b > 0) {
                        boolean prev = dir == 0 ? mod[a][b - 1] : mod[b - 1][a];
                        if (v == prev) run++;
                        else { if (run >= 5) p += 3 + run - 5; run = 1; }
                    }
                }
                if (run >= 5) p += 3 + run - 5;
                String s = sb.toString();
                p += 40 * (count(s, "10111010000") + count(s, "00001011101"));
            }
        }
        for (int y = 0; y < size - 1; y++)
            for (int x = 0; x < size - 1; x++)
                if (mod[y][x] == mod[y][x + 1] && mod[y][x] == mod[y + 1][x] && mod[y][x] == mod[y + 1][x + 1]) p += 3;
        int dark = 0;
        for (boolean[] row : mod) for (boolean v : row) if (v) dark++;
        int k = Math.abs(dark * 20 - size * size * 10) / (size * size);
        return p + k * 10;
    }
}
