package com.pindrop.app;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pindrop's phone-to-phone security. Built only from what Android already has.
 * <p>
 * Both phones share a 32-byte key (it travels in the pairing QR code). For every transfer the receiver first
 * hands out a single-use random nonce (GET /challenge), and the sender mixes it, plus its own random salt,
 * into fresh keys (AES-256-CTR for secrecy, HMAC-SHA256 for tamper checks). Because the nonce is single-use
 * and chosen by the receiver, a recorded upload can never be replayed.
 * The request body is: encrypt( [2-byte name length][file name][file bytes] ) followed by a 32-byte HMAC.
 * File names and contents are both hidden. A request without the right X-Pd-Auth tag is refused before
 * any data is read, and the answer carries X-Pd-Proof so the sender knows it reached the right phone.
 */
public final class Secure {
    public static final int MAC_LEN = 32;
    private static final SecureRandom RNG = new SecureRandom();

    private Secure() {}

    public interface SinkFactory {
        OutputStream open(String name) throws IOException;
    }

    public static byte[] newKey() {
        byte[] k = new byte[32];
        RNG.nextBytes(k);
        return k;
    }

    public static byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    public static String b64(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static byte[] unb64(String s) {
        return Base64.getUrlDecoder().decode(s);
    }

    private static Mac macOf(byte[] key) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return m;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** HMAC over several parts. Every part is length-prefixed, so ("ab","c") can never equal ("a","bc"). */
    public static byte[] hmac(byte[] key, byte[]... parts) {
        Mac m = macOf(key);
        for (byte[] p : parts) {
            m.update(new byte[] {(byte) (p.length >>> 24), (byte) (p.length >>> 16), (byte) (p.length >>> 8), (byte) p.length});
            m.update(p);
        }
        return m.doFinal();
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    /** A short tag tied to a label, the salt, the receiver's nonce and the body length. */
    public static byte[] tag(byte[] key, String label, byte[] salt, byte[] nonce, long total) {
        return hmac(key, ascii(label), salt, nonce, ascii(Long.toString(total)));
    }

    public static boolean same(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }

    private static final class Keys {
        final byte[] enc, mac;

        Keys(byte[] key, byte[] salt, byte[] nonce) {
            enc = hmac(key, ascii("enc"), salt, nonce);
            mac = hmac(key, ascii("mac"), salt, nonce);
        }

        Cipher cipher() {
            try {
                // Each transfer has its own key, so a fixed zero counter start is safe.
                Cipher c = Cipher.getInstance("AES/CTR/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(enc, "AES"), new IvParameterSpec(new byte[16]));
                return c;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }

    /** Sending side. */
    public static final class Sealer {
        private final byte[] key;
        private final byte[] nonce;
        private final byte[] salt = randomBytes(16);
        private final Cipher cipher;
        private final Mac mac;
        private final byte[] nameBytes;
        public final long total;

        public Sealer(byte[] key, byte[] nonce, String name, long size) {
            this.key = key;
            this.nonce = nonce;
            Keys k = new Keys(key, salt, nonce);
            cipher = k.cipher();
            mac = macOf(k.mac);
            mac.update(salt);
            mac.update(nonce);
            nameBytes = clipUtf8(name, 1000);
            total = 2L + nameBytes.length + size + MAC_LEN;
        }

        public void applyHeaders(HttpURLConnection c) {
            c.setRequestProperty("X-Pd-Salt", b64(salt));
            c.setRequestProperty("X-Pd-Nonce", b64(nonce));
            c.setRequestProperty("X-Pd-Auth", b64(tag(key, "auth", salt, nonce, total)));
        }

        /** True if the answer proves the other phone knows our shared key. */
        public boolean proofOk(HttpURLConnection c) {
            String got = c.getHeaderField("X-Pd-Proof");
            if (got == null) return false;
            try {
                return same(unb64(got), tag(key, "ok", salt, nonce, total));
            } catch (IllegalArgumentException e) {
                return false;
            }
        }

        public void begin(OutputStream out) throws IOException {
            byte[] h = new byte[2 + nameBytes.length];
            h[0] = (byte) (nameBytes.length >> 8);
            h[1] = (byte) nameBytes.length;
            System.arraycopy(nameBytes, 0, h, 2, nameBytes.length);
            write(out, h, h.length);
        }

        public void write(OutputStream out, byte[] buf, int n) throws IOException {
            byte[] ct = cipher.update(buf, 0, n);
            if (ct == null) return;
            mac.update(ct);
            out.write(ct);
        }

        public void end(OutputStream out) throws IOException {
            out.write(mac.doFinal());
        }
    }

    /** Cuts a name to at most max UTF-8 bytes without ever splitting a character in half. */
    public static byte[] clipUtf8(String name, int max) {
        byte[] nb = name.getBytes(StandardCharsets.UTF_8);
        if (nb.length <= max) return nb;
        int end = max;
        while (end > 0 && (nb[end] & 0xC0) == 0x80) end--; // step back to the start of a character
        return java.util.Arrays.copyOf(nb, end);
    }

    /** Proof the receiving phone puts in its answer. */
    public static byte[] proof(byte[] key, byte[] salt, byte[] nonce, long total) {
        return tag(key, "ok", salt, nonce, total);
    }

    /**
     * Receiving side. Reads exactly `total` bytes, decrypts as it goes, and hands the file bytes to the
     * sink. Returns true only if the closing HMAC matches, so the caller must throw the file away on false.
     */
    public static boolean open(InputStream in, byte[] key, byte[] salt, byte[] nonce, long total, SinkFactory factory)
            throws IOException {
        Keys k = new Keys(key, salt, nonce);
        Cipher cipher = k.cipher();
        Mac mac = macOf(k.mac);
        mac.update(salt);
        mac.update(nonce);
        long remaining = total - MAC_LEN;
        byte[] buf = new byte[64 * 1024];
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        OutputStream sink = null;
        while (remaining > 0) {
            int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
            if (n < 0) throw new IOException("Upload was cut off");
            remaining -= n;
            mac.update(buf, 0, n);
            byte[] pt = cipher.update(buf, 0, n);
            if (pt == null) continue;
            if (sink != null) { sink.write(pt); continue; }
            head.write(pt);
            byte[] h = head.toByteArray();
            if (h.length >= 2) {
                int nl = ((h[0] & 0xFF) << 8) | (h[1] & 0xFF);
                if (h.length >= 2 + nl) {
                    sink = factory.open(new String(h, 2, nl, StandardCharsets.UTF_8));
                    sink.write(h, 2 + nl, h.length - 2 - nl);
                }
            }
        }
        byte[] theirs = new byte[MAC_LEN];
        int got = 0;
        while (got < MAC_LEN) {
            int n = in.read(theirs, got, MAC_LEN - got);
            if (n < 0) throw new IOException("Upload was cut off");
            got += n;
        }
        return sink != null && same(theirs, mac.doFinal());
    }
}
