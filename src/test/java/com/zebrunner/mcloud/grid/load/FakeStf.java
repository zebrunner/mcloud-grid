package com.zebrunner.mcloud.grid.load;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Minimal STF API for load tests: devices of the fake nodes, reservation and release by the grid user,
 * a fixed latency of every request and request counters by endpoint.
 */
final class FakeStf implements AutoCloseable {
    static final String TOKEN = "fake-stf-token";
    private static final String USER = "grid";
    private static final Pattern SERIAL = Pattern.compile("\"serial\"\\s*:\\s*\"([^\"]+)\"");

    private final HttpServer server;
    private final long latencyMs;
    // serial -> owner name, "" when free
    private final Map<String, String> devices = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> requests = new ConcurrentHashMap<>();

    FakeStf(int port, long latencyMs) throws IOException {
        this.latencyMs = latencyMs;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.setExecutor(Executors.newCachedThreadPool());
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    void addDevice(String serial) {
        devices.put(serial, "");
    }

    /**
     * @return number of requests by "METHOD endpoint", serials replaced by {serial}
     */
    Map<String, Long> requests() {
        return new TreeMap<>(requests.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().get())));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String[] parts = path.split("/");
        String endpoint = method + " " + path.replaceAll("/(devices)/[^/]+", "/$1/{serial}");
        requests.computeIfAbsent(endpoint, e -> new AtomicLong()).incrementAndGet();
        try {
            if (latencyMs > 0) {
                Thread.sleep(latencyMs);
            }
            if (!("Bearer " + TOKEN).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                respond(exchange, 401, "{\"success\":false}");
            } else if ("GET".equals(method) && "/api/v1/user".equals(path)) {
                respond(exchange, 200, "{\"success\":true,\"user\":{\"name\":\"" + USER + "\",\"email\":\"grid@example.com\"}}");
            } else if ("GET".equals(method) && "/api/v1/devices".equals(path)) {
                respond(exchange, 200, "{\"success\":true,\"devices\":[" + devices.keySet().stream().map(this::device).collect(Collectors.joining(",")) + "]}");
            } else if ("GET".equals(method) && path.startsWith("/api/v1/devices/")) {
                String serial = parts[4];
                if (devices.containsKey(serial)) {
                    respond(exchange, 200, "{\"success\":true,\"device\":" + device(serial) + "}");
                } else {
                    // STF answers 500 for an unknown serial
                    respond(exchange, 500, "{\"success\":false}");
                }
            } else if ("POST".equals(method) && "/api/v1/user/devices".equals(path)) {
                Matcher serial = SERIAL.matcher(body);
                if (serial.find() && devices.replace(serial.group(1), "", USER)) {
                    respond(exchange, 200, "{\"success\":true}");
                } else {
                    respond(exchange, 403, "{\"success\":false,\"description\":\"Device is not available\"}");
                }
            } else if (path.endsWith("/remoteConnect")) {
                respond(exchange, 200, "{\"success\":true,\"remoteConnectUrl\":\"10.0.0.1:7401\"}");
            } else if ("DELETE".equals(method) && path.startsWith("/api/v1/user/devices/")) {
                devices.computeIfPresent(parts[5], (serial, owner) -> "");
                respond(exchange, 200, "{\"success\":true}");
            } else {
                respond(exchange, 404, "{\"success\":false}");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
        }
    }

    private String device(String serial) {
        String owner = devices.getOrDefault(serial, "");
        return "{\"serial\":\"" + serial + "\",\"present\":true,\"ready\":true,\"status\":3"
                + (owner.isEmpty() ? "" : ",\"owner\":{\"name\":\"" + owner + "\"}") + "}";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
