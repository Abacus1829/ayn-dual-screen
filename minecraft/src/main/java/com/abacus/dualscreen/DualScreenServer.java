package com.abacus.dualscreen;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The smallest HTTP server that will do.
 *
 * A raw {@link ServerSocket} rather than the JDK's HttpServer: this speaks a fixed handful of routes to
 * one page, and a socket loop is both fewer moving parts and identical to what the Stardew and Terraria
 * mods already do, so all three behave the same way when something goes wrong.
 *
 * Every request is answered on a worker thread. Nothing in here reads the game — it only hands out
 * strings the client thread finished earlier, and drops commands into a queue for that thread to drain.
 */
public final class DualScreenServer {

    private static volatile ServerSocket socket;
    private static volatile boolean running;

    private DualScreenServer() {
    }

    public static void start() {
        if (running) {
            return;
        }

        int port = DualScreenConfig.get().port.get();
        boolean lan = DualScreenConfig.get().allowLanAccess.get();

        try {
            // binding to the wildcard is what makes the handheld able to reach this at all; loopback is
            // the deliberate opt-out rather than the default
            InetAddress bind = lan ? null : InetAddress.getLoopbackAddress();
            socket = new ServerSocket();
            socket.setReuseAddress(true);
            socket.bind(bind == null ? new InetSocketAddress(port) : new InetSocketAddress(bind, port));
        } catch (IOException error) {
            AynDualScreen.LOG.error(
                    "[Ayn Dual Screen] Could not listen on port {}. Another program may already have it; "
                            + "change 'port' in the mod config and restart. ({})", port, error.getMessage());
            return;
        }

        running = true;
        announce(port, lan);

        Thread accepter = new Thread(DualScreenServer::acceptLoop, "AynDualScreen HTTP");
        accepter.setDaemon(true);
        accepter.start();
    }

    public static void stop() {
        running = false;
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // shutting down; a socket that refuses to close is not worth a stack trace
        }
    }

    private static void acceptLoop() {
        while (running) {
            try {
                Socket client = socket.accept();
                Thread worker = new Thread(() -> serve(client), "AynDualScreen request");
                worker.setDaemon(true);
                worker.start();
            } catch (IOException error) {
                if (running) {
                    AynDualScreen.LOG.warn("[Ayn Dual Screen] Accept failed: {}", error.getMessage());
                }
            }
        }
    }

    /** How long a waiting request is held before answering anyway. */
    private static final long HOLD_MS = 10_000;

    private static long parseLong(String text) {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException error) {
            return 0L;
        }
    }

    private static void serve(Socket client) {
        try (Socket open = client) {
            // comfortably longer than a held request, or the socket would time out mid-wait
            open.setSoTimeout((int) HOLD_MS + 5000);

            String line = readLine(open.getInputStream());
            if (line == null) {
                return;
            }

            String[] parts = line.split(" ");
            if (parts.length < 2) {
                return;
            }

            String path = parts[1];
            String query = "";
            int mark = path.indexOf('?');
            if (mark >= 0) {
                query = path.substring(mark + 1);
                path = path.substring(0, mark);
            }

            route(open.getOutputStream(), path, query);
        } catch (IOException ignored) {
            // a handheld that walks out of Wi-Fi range mid-request is normal, not an error worth logging
        }
    }

    private static void route(OutputStream out, String path, String query) throws IOException {
        switch (path) {
            case "/":
            case "/index.html":
                sendAsset(out, "index.html", "text/html; charset=utf-8");
                return;
            case "/app.js":
                sendAsset(out, "app.js", "text/javascript; charset=utf-8");
                return;
            case "/style.css":
                sendAsset(out, "style.css", "text/css; charset=utf-8");
                return;
            case "/state": {
                // ?since=<rev> holds the connection until there is something newer. Without it the
                // answer is immediate, which is what the app's own reachability check wants.
                String since = parseQuery(query).get("since");
                if (since == null) {
                    sendJson(out, DualScreenClient.stateJson());
                } else {
                    sendJson(out, DualScreenClient.awaitState(parseLong(since), HOLD_MS));
                }
                return;
            }
            case "/map":
                sendJson(out, DualScreenClient.mapJson());
                return;
            case "/tile": {
                Map<String, String> where = parseQuery(query);
                byte[] png = AtlasStore.png(
                        (int) parseLong(String.valueOf(where.get("x"))),
                        (int) parseLong(String.valueOf(where.get("z"))));
                if (png == null) {
                    send(out, 404, "text/plain; charset=utf-8", "no tile".getBytes(StandardCharsets.UTF_8));
                } else {
                    // the URL carries the revision, and a revision never changes, so let it cache hard
                    send(out, 200, "image/png", png, "public, max-age=3600, immutable");
                }
                return;
            }
            case "/recipes":
                // the list is built on the client thread when the page asks for it, so a request that
                // arrives before the next tick gets the previous answer rather than blocking
                DualScreenClient.enqueue(withAction(parseQuery(query), "recipes"));
                sendJson(out, DualScreenClient.recipeJson());
                return;
            case "/effect": {
                byte[] png = EffectIcons.get(parseQuery(query).get("id"));
                if (png == null) {
                    send(out, 404, "text/plain; charset=utf-8", "no icon".getBytes(StandardCharsets.UTF_8));
                } else {
                    send(out, 200, "image/png", png, "public, max-age=86400, immutable");
                }
                return;
            }
            case "/head": {
                byte[] png = HeadCache.get(parseQuery(query).get("type"));
                if (png == null) {
                    send(out, 404, "text/plain; charset=utf-8", "no head".getBytes(StandardCharsets.UTF_8));
                } else {
                    send(out, 200, "image/png", png, "public, max-age=86400, immutable");
                }
                return;
            }
            case "/action":
                DualScreenClient.enqueue(parseQuery(query));
                sendJson(out, "{\"ok\":true}");
                return;
            case "/icon":
                sendIcon(out, parseQuery(query).get("id"));
                return;
            default:
                send(out, 404, "text/plain; charset=utf-8", "not found".getBytes(StandardCharsets.UTF_8));
        }
    }

    /*********
     * Responses
     *********/
    private static void sendJson(OutputStream out, String json) throws IOException {
        send(out, 200, "application/json; charset=utf-8", json.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * One item's artwork.
     *
     * Cached hard by the browser, unlike everything else here: an item's texture cannot change while
     * the game is running, and re-fetching forty of them on every snapshot would be pointless traffic.
     */
    private static void sendIcon(OutputStream out, String itemId) throws IOException {
        byte[] png = itemId == null ? null : IconCache.get(itemId);
        if (png == null) {
            send(out, 404, "text/plain; charset=utf-8", "no icon".getBytes(StandardCharsets.UTF_8));
            return;
        }
        send(out, 200, "image/png", png, "public, max-age=86400, immutable");
    }

    /**
     * Serve one of the page's files straight out of the jar.
     *
     * Read fresh each time rather than cached: these are a few kilobytes, and it means editing the page
     * in a dev environment shows up on the next refresh instead of after a restart.
     */
    private static void sendAsset(OutputStream out, String name, String type) throws IOException {
        try (InputStream in = DualScreenServer.class.getResourceAsStream("/web/" + name)) {
            if (in == null) {
                send(out, 404, "text/plain; charset=utf-8", "missing asset".getBytes(StandardCharsets.UTF_8));
                return;
            }

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            send(out, 200, type, buffer.toByteArray());
        }
    }

    private static void send(OutputStream out, int status, String type, byte[] body) throws IOException {
        // the page polls hard; without this some browsers happily show a snapshot from ten seconds ago
        send(out, status, type, body, "no-store");
    }

    private static void send(OutputStream out, int status, String type, byte[] body, String cache)
            throws IOException {
        StringBuilder head = new StringBuilder();
        head.append("HTTP/1.1 ").append(status).append(status == 200 ? " OK" : " Error").append("\r\n");
        head.append("Content-Type: ").append(type).append("\r\n");
        head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("Cache-Control: ").append(cache).append("\r\n");
        head.append("Connection: close\r\n\r\n");

        out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(body);
        out.flush();
    }

    /*********
     * Parsing
     *********/
    private static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = in.read()) >= 0) {
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                line.append((char) value);
            }
            if (line.length() > 8192) {
                return null;
            }
        }
        return line.length() == 0 ? null : line.toString();
    }

    /** Tag a query with the action the client thread should carry out for it. */
    private static Map<String, String> withAction(Map<String, String> query, String action) {
        query.put("do", action);
        return query;
    }

    private static Map<String, String> parseQuery(String query) {
        Map<String, String> values = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return values;
        }

        for (String pair : query.split("&")) {
            int split = pair.indexOf('=');
            if (split <= 0) {
                continue;
            }
            String key = URLDecoder.decode(pair.substring(0, split), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8);
            values.put(key, value);
        }
        return values;
    }

    /*********
     * Announcing
     *********/
    /**
     * Print the address that actually works from another device.
     *
     * Logging "http://localhost:27302/" would be actively misleading: typed into a handheld that points
     * it at itself, which is the single most common way this fails to connect.
     */
    private static void announce(int port, boolean lan) {
        AynDualScreen.LOG.info("[Ayn Dual Screen] Second screen is up on port {}.", port);

        if (!lan) {
            AynDualScreen.LOG.info(
                    "[Ayn Dual Screen] LAN access is off, so only this PC can reach it: http://localhost:{}/", port);
            AynDualScreen.LOG.info(
                    "[Ayn Dual Screen] Set allowLanAccess = true in the config to use it from a handheld.");
            return;
        }

        List<String> addresses = localAddresses();
        if (addresses.isEmpty()) {
            AynDualScreen.LOG.warn(
                    "[Ayn Dual Screen] No network address found. Is this PC connected to Wi-Fi or Ethernet?");
            return;
        }

        AynDualScreen.LOG.info("[Ayn Dual Screen] On your handheld, open:  http://{}:{}/", addresses.get(0), port);
        for (int i = 1; i < addresses.size(); i++) {
            AynDualScreen.LOG.info("[Ayn Dual Screen]   or try:            http://{}:{}/", addresses.get(i), port);
        }
        if (addresses.size() > 1) {
            AynDualScreen.LOG.info(
                    "[Ayn Dual Screen] More than one address usually means a VPN is running. If the first "
                            + "doesn't work, the VPN is probably in the way - try the others, or turn it off.");
        }
    }

    /** Every usable IPv4 address on this machine, most likely first. */
    private static List<String> localAddresses() {
        List<String> found = new ArrayList<>();
        try {
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
                    continue;
                }

                for (InetAddress address : Collections.list(network.getInetAddresses())) {
                    if (!(address instanceof Inet4Address) || address.isLoopbackAddress()) {
                        continue;
                    }

                    String text = address.getHostAddress();
                    // 192.168.x and 10.x are the ones a handheld on the same Wi-Fi can actually reach
                    if (text.startsWith("192.168.") || text.startsWith("10.")) {
                        found.add(0, text);
                    } else {
                        found.add(text);
                    }
                }
            }
        } catch (Exception error) {
            AynDualScreen.LOG.warn("[Ayn Dual Screen] Could not list network addresses: {}",
                    error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        }
        return found;
    }

    /** Lower-cased helper so route matching never depends on the platform's locale. */
    static String lower(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT);
    }
}
