package com.pindrop.app;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Small web server that lets another paired phone send files here. It speaks the same /ping and /upload
 * as the laptop program, but every request must carry the pairing key (see Secure).
 * Pure Java on purpose: no Android classes, so it can be tested on a computer.
 */
public final class PeerServer {
    public interface Sink {
        OutputStream stream();

        /** Called once. ok=true keeps the file, ok=false throws it away. */
        void finish(boolean ok);
    }

    public interface Host {
        /** The long-lived key of this phone (used for pings and normal pairing). */
        byte[] key();

        /** A throw-away key for a "no Wi-Fi" session, or null. While set, ONLY this key is accepted for uploads. */
        byte[] sessionKey();

        String deviceName();

        Sink openFile(String cleanName) throws IOException;

        void received(String name, long size);

        void paired(String name, String host, int port, String keyB64);
    }

    private static final int MAX_HEADERS = 64;
    private static final int MAX_WORKERS = 12;
    private static final long NONCE_LIFETIME_MS = 30_000L;
    private static final int MAX_NONCES = 256;
    private static final SecureRandom RNG = new SecureRandom();

    private final Host host;
    private ServerSocket server;
    private ExecutorService pool;
    public volatile int port;

    /** Single-use challenges handed out by GET /challenge: nonce (base64) -> time it was issued. */
    private final Map<String, Long> nonces = new HashMap<>();

    private String issueNonce() {
        byte[] n = new byte[16];
        RNG.nextBytes(n);
        String s = Secure.b64(n);
        long now = System.currentTimeMillis();
        synchronized (nonces) {
            Iterator<Map.Entry<String, Long>> it = nonces.entrySet().iterator();
            while (it.hasNext()) if (now - it.next().getValue() > NONCE_LIFETIME_MS) it.remove();
            if (nonces.size() >= MAX_NONCES) return null;
            nonces.put(s, now);
        }
        return s;
    }

    /** True exactly once for a nonce we issued in the last 30 seconds. */
    private boolean consumeNonce(String s) {
        synchronized (nonces) {
            Long t = nonces.remove(s);
            return t != null && System.currentTimeMillis() - t <= NONCE_LIFETIME_MS;
        }
    }

    public PeerServer(Host host) {
        this.host = host;
    }

    public synchronized void start(int preferredPort) {
        if (server != null) return;
        ServerSocket s;
        try {
            s = new ServerSocket(preferredPort);
        } catch (IOException e) {
            try {
                s = new ServerSocket(0);
            } catch (IOException e2) {
                return;
            }
        }
        server = s;
        port = s.getLocalPort();
        pool = new ThreadPoolExecutor(0, MAX_WORKERS, 30L, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(), r -> {
            Thread t = new Thread(r, "pindrop-peer");
            t.setDaemon(true);
            return t;
        });
        final ServerSocket ss = s;
        Thread acceptor = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    final Socket c = ss.accept();
                    try {
                        pool.execute(() -> handle(c));
                    } catch (RejectedExecutionException busy) {
                        try { c.close(); } catch (IOException ignored) { } // too many at once: drop the extra
                    }
                } catch (IOException e) {
                    break;
                }
            }
        }, "pindrop-accept");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public synchronized void stop() {
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        if (pool != null) pool.shutdownNow();
        server = null;
        pool = null;
        port = 0;
    }

    // ------------------------------------------------------------------ HTTP

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int b = in.read();
            if (b < 0) return sb.length() == 0 ? null : sb.toString();
            if (b == '\n') break;
            if (b != '\r') sb.append((char) b);
            if (sb.length() > 8192) return null;
        }
        return sb.toString();
    }

    private static void respond(OutputStream out, int code, String text, Map<String, String> extra) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(code == 200 ? " OK" : " Error").append("\r\n");
        sb.append("Content-Type: text/plain; charset=utf-8\r\n");
        sb.append("Content-Length: ").append(body.length).append("\r\n");
        sb.append("Connection: close\r\n");
        for (Map.Entry<String, String> e : extra.entrySet())
            sb.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
        sb.append("\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body);
        out.flush();
    }

    private static Map<String, String> none() {
        return new HashMap<>();
    }

    private void handle(Socket sock) {
        try {
            sock.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(sock.getInputStream(), 64 * 1024);
            OutputStream out = new BufferedOutputStream(sock.getOutputStream());
            String line = readLine(in);
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            Map<String, String> h = new HashMap<>();
            while (true) {
                String l = readLine(in);
                if (l == null) return;
                if (l.isEmpty()) break;
                if (h.size() >= MAX_HEADERS) return;
                int i = l.indexOf(':');
                if (i > 0) h.put(l.substring(0, i).trim().toLowerCase(), l.substring(i + 1).trim());
            }
            String method = parts[0], full = parts[1];
            int q = full.indexOf('?');
            String path = q < 0 ? full : full.substring(0, q);
            String query = q < 0 ? "" : full.substring(q + 1);
            if (method.equals("GET") && path.equals("/ping")) {
                ping(out, query);
            } else if (method.equals("GET") && path.equals("/challenge")) {
                String n = issueNonce();
                if (n == null) respond(out, 503, "Busy", none());
                else respond(out, 200, n, none());
            } else if (method.equals("POST") && (path.equals("/upload") || path.equals("/pair"))) {
                sealed(sock, in, out, h, path.equals("/pair"));
            } else {
                respond(out, 404, "Not found", none());
            }
        } catch (Exception ignored) {
        } finally {
            try {
                sock.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void ping(OutputStream out, String query) throws IOException {
        Map<String, String> extra = new HashMap<>();
        extra.put("X-Pd-Name", URLEncoder.encode(host.deviceName(), "UTF-8"));
        if (query.startsWith("c=")) {
            try {
                byte[] nonce = Secure.unb64(query.substring(2));
                extra.put("X-Pd-Proof", Secure.b64(Secure.hmac(host.key(), "ping".getBytes(StandardCharsets.US_ASCII), nonce)));
            } catch (IllegalArgumentException ignored) {
            }
        }
        respond(out, 200, "PINDROP_PHONE", extra);
    }

    static String cleanName(String name) {
        String n = name.replace('\\', '/');
        n = n.substring(n.lastIndexOf('/') + 1).replaceAll("[<>:\"|?*\\x00-\\x1f]", "_").trim();
        while (n.startsWith(".") || n.endsWith(".")) n = n.replaceAll("^\\.+|\\.+$", "").trim();
        if (n.length() > 120) n = n.substring(0, 120);
        return n.isEmpty() ? "file" : n;
    }

    /** A name that is safe to show and store: no control characters, at most 30 characters. */
    static String cleanDeviceName(String n) {
        String c = n.replaceAll("[\\p{Cntrl}<>]", "").trim();
        if (c.length() > 30) c = c.substring(0, 30);
        return c.isEmpty() ? "Phone" : c;
    }

    private static boolean goodKey(String k) {
        try {
            return Secure.unb64(k).length == 32;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Counts the bytes that really get written, so the size we report is exact. */
    private static final class CountingOut extends OutputStream {
        final OutputStream inner;
        long count;

        CountingOut(OutputStream inner) {
            this.inner = inner;
        }

        @Override public void write(int b) throws IOException { inner.write(b); count++; }

        @Override public void write(byte[] b, int off, int len) throws IOException { inner.write(b, off, len); count += len; }

        @Override public void flush() throws IOException { inner.flush(); }

        @Override public void close() throws IOException { inner.close(); }
    }

    private void sealed(Socket sock, InputStream in, OutputStream out, Map<String, String> h, boolean isPair)
            throws IOException {
        String saltS = h.get("x-pd-salt"), nonceS = h.get("x-pd-nonce"), authS = h.get("x-pd-auth"), lenS = h.get("content-length");
        long total;
        byte[] salt, nonce, auth;
        try {
            total = Long.parseLong(lenS);
            salt = Secure.unb64(saltS);
            nonce = Secure.unb64(nonceS);
            auth = Secure.unb64(authS);
        } catch (RuntimeException e) {
            respond(out, 400, "Bad request", none());
            return;
        }
        if (salt.length != 16 || nonce.length != 16 || total < 2 + Secure.MAC_LEN || (isPair && total > 8192)) {
            respond(out, 400, "Bad request", none());
            return;
        }
        byte[] session = host.sessionKey();
        byte[] key = session != null ? session : host.key();
        if (!Secure.same(auth, Secure.tag(key, "auth", salt, nonce, total))) {
            respond(out, 403, "Not paired with this phone", none());
            return;
        }
        // The nonce is spent only after the sender proved it knows the key, so strangers can't burn them.
        if (!consumeNonce(nonceS)) {
            respond(out, 403, "Expired. Try again.", none());
            return;
        }
        Map<String, String> ok = new HashMap<>();
        ok.put("X-Pd-Proof", Secure.b64(Secure.proof(key, salt, nonce, total)));

        if (isPair) {
            final ByteArrayOutputStream mem = new ByteArrayOutputStream();
            boolean good = Secure.open(in, key, salt, nonce, total, name -> mem);
            if (!good) {
                respond(out, 403, "Bad data", none());
                return;
            }
            String body = new String(mem.toByteArray(), StandardCharsets.UTF_8);
            String name = "Phone", keyB64 = null;
            int port = 0;
            try {
                for (String kv : body.split("&")) {
                    int i = kv.indexOf('=');
                    if (i < 0) continue;
                    String k = kv.substring(0, i), v = URLDecoder.decode(kv.substring(i + 1), "UTF-8");
                    if (k.equals("n")) name = cleanDeviceName(v);
                    else if (k.equals("p")) {
                        try { port = Integer.parseInt(v.trim()); } catch (NumberFormatException e) { port = 0; }
                    } else if (k.equals("k")) keyB64 = v;
                }
            } catch (IllegalArgumentException e) {
                respond(out, 400, "Bad data", none());
                return;
            }
            if (keyB64 == null || !goodKey(keyB64) || port < 1 || port > 65535) {
                respond(out, 400, "Bad data", none());
                return;
            }
            String remote = ((InetSocketAddress) sock.getRemoteSocketAddress()).getAddress().getHostAddress();
            host.paired(name, remote, port, keyB64);
            respond(out, 200, "OK", ok);
            return;
        }

        final Sink[] sinkRef = new Sink[1];
        final String[] nameRef = new String[1];
        final CountingOut[] counter = new CountingOut[1];
        boolean good;
        try {
            good = Secure.open(in, key, salt, nonce, total, name -> {
                nameRef[0] = cleanName(name);
                sinkRef[0] = host.openFile(nameRef[0]);
                counter[0] = new CountingOut(sinkRef[0].stream());
                return counter[0];
            });
        } catch (IOException e) {
            if (sinkRef[0] != null) sinkRef[0].finish(false);
            respond(out, 500, "Could not save the file", none());
            return;
        }
        if (!good) {
            if (sinkRef[0] != null) sinkRef[0].finish(false);
            respond(out, 403, "The file was damaged on the way", none());
            return;
        }
        sinkRef[0].finish(true);
        host.received(nameRef[0], counter[0] == null ? 0 : counter[0].count);
        respond(out, 200, "OK", ok);
    }
}
