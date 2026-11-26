/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 *
 * Modified for HushGram (Instagram), 2026.
 */
package app.hushgram.extension.instagram.download;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A one-connection-at-a-time HTTP server on the loopback address, for the download tests.
 *
 * <p>Hand written because the JDK's own server isn't on Android's unit-test classpath, and because
 * the cases need what a real server won't do on request: announce one length and send another,
 * or send a body with no length at all.
 */
final class LocalServer implements Closeable {

    private static final class Route {
        final int code;
        final String type;
        final byte[] body;
        final long announced;
        final String location;
        /** A body made as it goes out: its whole length, or -1 when [body] is the whole answer. */
        final long generated;
        final long trickleAfter;
        final CountDownLatch release;
        /** Past [trickleAfter], send nothing until released rather than a trickle. */
        final boolean hold;

        Route(int code, String type, byte[] body, long announced, String location) {
            this(code, type, body, announced, location, -1, Long.MAX_VALUE, null);
        }

        Route(int code, String type, byte[] body, long announced, String location, long generated,
                long trickleAfter, CountDownLatch release) {
            this(code, type, body, announced, location, generated, trickleAfter, release, false);
        }

        Route(int code, String type, byte[] body, long announced, String location, long generated,
                long trickleAfter, CountDownLatch release, boolean hold) {
            this.code = code;
            this.type = type;
            this.body = body;
            this.announced = announced;
            this.location = location;
            this.generated = generated;
            this.trickleAfter = trickleAfter;
            this.release = release;
            this.hold = hold;
        }
    }

    /** How much a trickle sends at a time, and how often. */
    private static final int TRICKLE_BYTES = 64 * 1024;
    private static final long TRICKLE_EVERY_MS = 200;

    private final ServerSocket socket;
    private final Map<String, Route> routes = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();
    /** Routes whose body never ends, and how many bytes each sends at a time. */
    private final Map<String, Integer> endless = new ConcurrentHashMap<>();
    /** Routes whose next answer ends after this many bytes of the body, once. */
    private final Map<String, Integer> cutOnce = new ConcurrentHashMap<>();
    /** Routes never answered. */
    private final Set<String> stalled = ConcurrentHashMap.newKeySet();
    /** Routes that answer a {@code Range: bytes=N-} request with a 206 from byte N. */
    private final Set<String> ranged = ConcurrentHashMap.newKeySet();
    /** For a ranged route, where each answer in turn ends: a byte of the whole body. */
    private final Map<String, Deque<Integer>> cutsAt = new ConcurrentHashMap<>();
    /** For a ranged route, the byte its next answer stops at and goes quiet until the client goes. */
    private final Map<String, Integer> stallOnce = new ConcurrentHashMap<>();
    /** For a ranged route, the Content-Range its 206 claims in place of the true one. */
    private final Map<String, String> claimedRange = new ConcurrentHashMap<>();
    /** The Range header of each request for a route, in order, or "" for a request with none. */
    private final Map<String, List<String>> asked = new ConcurrentHashMap<>();
    /** Counted down when a route's client let go of its connection before the server did. */
    private final Map<String, CountDownLatch> gone = new ConcurrentHashMap<>();
    private final Thread thread;

    LocalServer() throws IOException {
        socket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        thread = new Thread(this::serve, "local-server");
        thread.setDaemon(true);
        thread.start();
    }

    int port() {
        return socket.getLocalPort();
    }

    String origin() {
        return "http://127.0.0.1:" + port();
    }

    /** Answer [path] with [body] as [type]; [announced] is the length to claim, or -1 for none. */
    void serve(String path, int code, String type, byte[] body, long announced) {
        routes.put(path, new Route(code, type, body, announced, null));
    }

    void serve(String path, String type, byte[] body) {
        serve(path, 200, type, body, body.length);
    }

    /**
     * Answer [path] with [length] bytes, all announced, that start with [head] and go on with a
     * made-up pattern. After [trickleAfter] bytes only 64 KiB goes out every 200 ms until
     * [release] is counted down, then the rest at once. The body is made as it's sent, so a
     * 100 MiB answer needs no 100 MiB array, and a save of it stays running while a test looks.
     */
    void serveGenerated(String path, String type, byte[] head, long length, long trickleAfter, CountDownLatch release) {
        routes.put(path, new Route(200, type, head, length, null, length, trickleAfter, release));
    }

    /**
     * Like {@link #serveGenerated}, but after [holdAfter] bytes it sends nothing at all until
     * [release] is counted down, so the client's read waits on the network.
     */
    void serveHeld(String path, String type, byte[] head, long length, long holdAfter, CountDownLatch release) {
        routes.put(path, new Route(200, type, head, length, null, length, holdAfter, release, true));
    }

    /**
     * Answer [path] with [body], all of it announced, but end the first answer after [cutAfter]
     * bytes of it, the way a phone's network can drop a long read (#546). Later requests get it all.
     */
    void serveCutOnce(String path, String type, byte[] body, int cutAfter) {
        serve(path, type, body);
        cutOnce.put(path, cutAfter);
    }

    /**
     * Answer [path] with [body], announced whole, and a {@code Range: bytes=N-} request with a 206
     * of the rest from byte N, the way Meta's servers do. Each answer in turn ends at the next of
     * [cuts], a byte of the whole body, as a network that keeps dropping a long read does (#792).
     */
    void serveResumable(String path, String type, byte[] body, int... cuts) {
        serve(path, type, body);
        ranged.add(path);
        Deque<Integer> queue = new ArrayDeque<>();
        for (int cut : cuts) queue.add(cut);
        cutsAt.put(path, queue);
    }

    /**
     * Like {@link #serveResumable} with no cuts, but the first answer stops at byte [stallAt] and
     * sends nothing more until the client gives up on it: a read that times out partway (#792).
     */
    void serveStallingOnce(String path, String type, byte[] body, int stallAt) {
        serveResumable(path, type, body);
        stallOnce.put(path, stallAt);
    }

    /** A resumable [path] whose 206 claims [contentRange], whatever it was asked for. */
    void claimRange(String path, String contentRange) {
        claimedRange.put(path, contentRange);
    }

    /** The Range header of each request for [path] so far, "" for one that sent none. */
    List<String> rangesAsked(String path) {
        List<String> list = asked.get(path);
        return list == null ? new ArrayList<>() : new ArrayList<>(list);
    }

    void redirect(String path, String location) {
        routes.put(path, new Route(302, null, new byte[0], 0, location));
    }

    /**
     * Answer [path] with [code] and a body of no stated length that never ends: [tick] bytes every
     * 200 ms for as long as the client reads, as an error page on a stalled connection can.
     */
    void serveEndless(String path, int code, String type, int tick) {
        routes.put(path, new Route(code, type, new byte[0], -1, null));
        endless.put(path, tick);
    }

    /** A redirect to [location] whose body never ends, like {@link #serveEndless}'s. */
    void redirectEndless(String path, String location, int tick) {
        routes.put(path, new Route(302, "text/html", new byte[0], -1, location));
        endless.put(path, tick);
    }

    /**
     * Take the request for [path] and never answer. Not even a status line: the JDK's connection
     * reports the status of one that came, and a read timeout after it, as that status and no
     * failure, so only an answer that never starts makes its header read throw here.
     */
    void neverAnswer(String path) {
        stalled.add(path);
    }

    /**
     * Whether the client of [path] let go of its connection within [ms]: it closed it while the
     * body was still going out, or while it waited for an answer that never came.
     */
    boolean letGoWithin(String path, long ms) throws InterruptedException {
        return gone(path).await(ms, TimeUnit.MILLISECONDS);
    }

    private CountDownLatch gone(String path) {
        return gone.computeIfAbsent(path, k -> new CountDownLatch(1));
    }

    int hits(String path) {
        AtomicInteger n = hits.get(path);
        return n == null ? 0 : n.get();
    }

    private void serve() {
        while (!socket.isClosed()) {
            try (Socket client = socket.accept()) {
                client.setSoTimeout(10_000);
                answer(client);
            } catch (IOException ignored) {
                // A client that stopped reading, or the server closing.
            }
        }
    }

    private void answer(Socket client) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(client.getInputStream(), StandardCharsets.ISO_8859_1));
        String request = in.readLine();
        if (request == null) return;
        String line;
        String range = "";
        while ((line = in.readLine()) != null && !line.isEmpty()) {
            // Only a Range header matters here.
            if (line.regionMatches(true, 0, "Range:", 0, 6)) range = line.substring(6).trim();
        }
        String[] parts = request.split(" ");
        String path = parts.length > 1 ? parts[1] : "/";
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        hits.computeIfAbsent(path, k -> new AtomicInteger()).incrementAndGet();
        asked.computeIfAbsent(path, k -> new CopyOnWriteArrayList<>()).add(range);
        if (stalled.contains(path)) {
            stall(client, path);
            return;
        }

        Route route = routes.get(path);
        if (route == null) route = new Route(404, "text/plain", "missing".getBytes(StandardCharsets.UTF_8), 7, null);
        if (ranged.contains(path)) {
            answerRanged(client, path, route, range);
            return;
        }

        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(route.code).append(" X\r\n");
        head.append("Connection: close\r\n");
        if (route.type != null) head.append("Content-Type: ").append(route.type).append("\r\n");
        if (route.location != null) head.append("Location: ").append(route.location).append("\r\n");
        if (route.announced >= 0) head.append("Content-Length: ").append(route.announced).append("\r\n");
        head.append("\r\n");

        OutputStream out = client.getOutputStream();
        Integer cut = cutOnce.remove(path);
        if (cut != null) {
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(route.body, 0, Math.min(cut, route.body.length));
            out.flush();
            return;
        }
        try {
            out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(route.body);
            if (route.generated >= 0) sendGenerated(out, route);
            Integer tick = endless.get(path);
            if (tick != null) sendEndless(out, tick);
            out.flush();
        } catch (IOException e) {
            gone(path).countDown();
            throw e;
        }
    }

    /** A ranged route's answer: all of it, or a 206 from the byte a Range asked for. */
    private void answerRanged(Socket client, String path, Route route, String range) throws IOException {
        byte[] body = route.body;
        int start = 0;
        if (range.startsWith("bytes=") && range.endsWith("-")) {
            start = Integer.parseInt(range.substring(6, range.length() - 1));
        }
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(start > 0 ? 206 : 200).append(" X\r\n");
        head.append("Connection: close\r\n");
        head.append("Content-Type: ").append(route.type).append("\r\n");
        if (start > 0) {
            String claimed = claimedRange.get(path);
            head.append("Content-Range: ")
                .append(claimed != null ? claimed : "bytes " + start + "-" + (body.length - 1) + "/" + body.length)
                .append("\r\n");
        }
        head.append("Content-Length: ").append(body.length - start).append("\r\n\r\n");

        Deque<Integer> cuts = cutsAt.get(path);
        Integer cut = cuts == null ? null : cuts.poll();
        Integer stallAt = stallOnce.remove(path);
        int end = stallAt != null ? stallAt : cut != null ? cut : body.length;
        OutputStream out = client.getOutputStream();
        out.write(head.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.write(body, start, Math.max(0, end - start));
        out.flush();
        if (stallAt != null) stall(client, path);
    }

    /** [tick] bytes every 200 ms until the client goes away or the server closes. */
    private void sendEndless(OutputStream out, int tick) throws IOException {
        byte[] chunk = new byte[tick];
        java.util.Arrays.fill(chunk, (byte) '.');
        while (!socket.isClosed()) {
            out.write(chunk);
            out.flush();
            try {
                Thread.sleep(TRICKLE_EVERY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Nothing, until the client lets go or 10 s go by. */
    private void stall(Socket client, String path) {
        try {
            while (client.getInputStream().read() >= 0) {
                // The client sends nothing more. This only waits for it to go.
            }
        } catch (java.net.SocketTimeoutException e) {
            return;
        } catch (IOException e) {
            // Reset rather than closed: gone all the same.
        }
        gone(path).countDown();
    }

    /** The rest of a generated body. A client that goes away ends it with an IOException. */
    private static void sendGenerated(OutputStream out, Route route) throws IOException {
        byte[] chunk = new byte[TRICKLE_BYTES];
        long sent = route.body.length;
        while (sent < route.generated) {
            boolean trickling = sent >= route.trickleAfter && route.release != null && route.release.getCount() > 0;
            if (trickling && route.hold) {
                out.flush();
                try {
                    route.release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                continue;
            }
            int size = (int) Math.min(chunk.length, route.generated - sent);
            for (int i = 0; i < size; i++) chunk[i] = (byte) ((sent + i) * 13);
            out.write(chunk, 0, size);
            sent += size;
            if (trickling) {
                out.flush();
                try {
                    route.release.await(TRICKLE_EVERY_MS, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
