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
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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
        byte[] key();

        String deviceName();

        Sink openFile(String cleanName) throws IOException;

        void received(String name, long size);

        void paired(String name, String host, int port, String keyB64);
    }

    private final Host host;
    private ServerSocket server;
    private ExecutorService pool;
    public volatile int port;

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
        pool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "pindrop-peer");
            t.setDaemon(true);
            return t;
        });
        final ServerSocket ss = s;
        Thread acceptor = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    final Socket c = ss.accept();
                    pool.execute(() -> handle(c));
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
                int i = l.indexOf(':');
                if (i > 0) h.put(l.substring(0, i).trim().toLowerCase(), l.substring(i + 1).trim());
            }
            String method = parts[0], full = parts[1];
            int q = full.indexOf('?');
            String path = q < 0 ? full : full.substring(0, q);
            String query = q < 0 ? "" : full.substring(q + 1);
            if (method.equals("GET") && path.equals("/ping")) {
                ping(out, query);
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

    private void sealed(Socket sock, InputStream in, OutputStream out, Map<String, String> h, boolean isPair)
            throws IOException {
        String saltS = h.get("x-pd-salt"), authS = h.get("x-pd-auth"), lenS = h.get("content-length");
        long total;
        byte[] salt, auth;
        try {
            total = Long.parseLong(lenS);
            salt = Secure.unb64(saltS);
            auth = Secure.unb64(authS);
        } catch (RuntimeException e) {
            respond(out, 400, "Bad request", none());
            return;
        }
        if (total < 2 + Secure.MAC_LEN || (isPair && total > 8192)) {
            respond(out, 400, "Bad request", none());
            return;
        }
        byte[] key = host.key();
        if (!Secure.same(auth, Secure.tag(key, "auth", salt, total))) {
            respond(out, 403, "Not paired with this phone", none());
            return;
        }
        Map<String, String> ok = new HashMap<>();
        ok.put("X-Pd-Proof", Secure.b64(Secure.proof(key, salt, total)));

        if (isPair) {
            final ByteArrayOutputStream mem = new ByteArrayOutputStream();
            boolean good = Secure.open(in, key, salt, total, name -> mem);
            if (!good) {
                respond(out, 403, "Bad data", none());
                return;
            }
            String body = new String(mem.toByteArray(), StandardCharsets.UTF_8);
            String name = "Phone", keyB64 = null;
            int port = 0;
            for (String kv : body.split("&")) {
                int i = kv.indexOf('=');
                if (i < 0) continue;
                String k = kv.substring(0, i), v = URLDecoder.decode(kv.substring(i + 1), "UTF-8");
                if (k.equals("n")) name = v;
                else if (k.equals("p")) port = Integer.parseInt(v);
                else if (k.equals("k")) keyB64 = v;
            }
            if (keyB64 == null || port <= 0) {
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
        final int[] nameLen = new int[1];
        boolean good;
        try {
            good = Secure.open(in, key, salt, total, name -> {
                nameLen[0] = name.getBytes(StandardCharsets.UTF_8).length;
                nameRef[0] = cleanName(name);
                sinkRef[0] = host.openFile(nameRef[0]);
                return sinkRef[0].stream();
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
        long size = total - 2 - Secure.MAC_LEN - nameLen[0];
        host.received(nameRef[0], Math.max(0, size));
        respond(out, 200, "OK", ok);
    }
}
