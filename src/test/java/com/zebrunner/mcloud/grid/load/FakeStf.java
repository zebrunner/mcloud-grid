package com.zebrunner.mcloud.grid.load;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Minimal STF API for load tests: the node registry of the fake nodes (their node tokens verified for their address),
 * devices of the fake nodes, reservation and release by the grid user, a fixed latency of every request and request
 * counters by endpoint.
 */
final class FakeStf implements AutoCloseable {
    static final String TOKEN = "fake-stf-token";
    private static final String USER = "grid";
    private static final Pattern SERIAL = Pattern.compile("\"serial\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern VERIFY_TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern VERIFY_HOST = Pattern.compile("\"host\"\\s*:\\s*\"([^\"]+)\"");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final HttpServer server;
    private final long latencyMs;
    // serial -> owner name, "" when free
    private final Map<String, String> devices = new ConcurrentHashMap<>();
    // serial -> label of the node the device is bound to
    private final Map<String, String> deviceNodes = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> requests = new ConcurrentHashMap<>();
    // node token -> address of the node
    private final Map<String, String> nodes = new ConcurrentHashMap<>();

    FakeStf(int port, long latencyMs) throws IOException {
        this.latencyMs = latencyMs;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.setExecutor(Executors.newCachedThreadPool());
        this.server.createContext("/", this::handle);
        this.server.start();
    }

    /**
     * Enrolls a node at the address, as POST /api/v1/nodes/enroll of STF does.
     *
     * @return the node token of the node: mcn1.<label>.<32 random bytes base64url>
     */
    String enroll(String label, String address) {
        byte[] secret = new byte[32];
        RANDOM.nextBytes(secret);
        String token = "mcn1." + label + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        nodes.put(token, address.toLowerCase(Locale.ROOT));
        return token;
    }

    /**
     * Adds a device bound to the node of the label, as STF binds the devices of an enrolled node.
     */
    void addDevice(String serial, String label) {
        deviceNodes.put(serial, label);
        devices.put(serial, "");
    }

    /**
     * @return number of the devices reserved now
     */
    long reservedDevices() {
        return devices.values().stream().filter(owner -> !owner.isEmpty()).count();
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
            } else if ("POST".equals(method) && "/api/v1/nodes/verify".equals(path)) {
                Matcher token = VERIFY_TOKEN.matcher(body);
                Matcher host = VERIFY_HOST.matcher(body);
                String address = token.find() ? nodes.get(token.group(1)) : null;
                if (address != null && host.find() && address.equals(host.group(1).toLowerCase(Locale.ROOT))) {
                    String label = token.group(1).split("\\.")[1];
                    respond(exchange, 200, "{\"label\":\"" + label + "\",\"address\":\"" + address + "\"}");
                } else {
                    respond(exchange, 403, "{\"success\":false}");
                }
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
        return "{\"serial\":\"" + serial + "\",\"present\":true,\"ready\":true,\"status\":3,\"node\":\"" + deviceNodes.get(serial) + "\""
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
