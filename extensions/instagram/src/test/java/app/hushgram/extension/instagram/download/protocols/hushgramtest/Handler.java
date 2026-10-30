/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.download.protocols.hushgramtest;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/** Per-test response streams without replacing the JVM's one-shot URL factory. */
public final class Handler extends URLStreamHandler {
    private static final Map<String, Function<URL, HttpURLConnection>> CONNECTIONS = new ConcurrentHashMap<>();

    public static void register(String host, Function<URL, HttpURLConnection> connection) {
        CONNECTIONS.put(host, connection);
    }

    public static void remove(String host) {
        CONNECTIONS.remove(host);
    }

    @Override protected URLConnection openConnection(URL url) throws IOException {
        Function<URL, HttpURLConnection> connection = CONNECTIONS.get(url.getHost());
        if (connection == null) throw new IOException("No response registered for this test");
        return connection.apply(url);
    }
}
