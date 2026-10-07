package com.zebrunner.mcloud.grid.load;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Aggregated load test results: latency percentiles per step and error breakdown.
 */
final class LoadReport {

    /** Outcome of one session lifecycle. */
    static final class SessionResult {
        final boolean success;
        final String error;
        final long createMs;
        final List<Long> commandMs;
        final long deleteMs;

        SessionResult(boolean success, String error, long createMs, List<Long> commandMs, long deleteMs) {
            this.success = success;
            this.error = error;
            this.createMs = createMs;
            this.commandMs = commandMs;
            this.deleteMs = deleteMs;
        }
    }

    private final List<SessionResult> results;
    private final long wallClockMs;

    LoadReport(List<SessionResult> results, long wallClockMs) {
        this.results = results;
        this.wallClockMs = wallClockMs;
    }

    long total() {
        return results.size();
    }

    long failed() {
        return results.stream().filter(r -> !r.success).count();
    }

    double errorRate() {
        return results.isEmpty() ? 0 : (double) failed() / results.size();
    }

    Map<String, Long> errors() {
        return results.stream().filter(r -> !r.success)
                .collect(Collectors.groupingBy(r -> r.error, TreeMap::new, Collectors.counting()));
    }

    List<Long> createLatencies() {
        return results.stream().filter(r -> r.createMs >= 0).map(r -> r.createMs).collect(Collectors.toList());
    }

    private List<Long> commandLatencies() {
        return results.stream().flatMap(r -> r.commandMs.stream()).collect(Collectors.toList());
    }

    private List<Long> deleteLatencies() {
        return results.stream().filter(r -> r.deleteMs >= 0).map(r -> r.deleteMs).collect(Collectors.toList());
    }

    static long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return -1;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private static String stats(List<Long> values) {
        return String.format("{\"count\":%d,\"p50\":%d,\"p95\":%d,\"p99\":%d,\"max\":%d}", values.size(),
                percentile(values, 50), percentile(values, 95), percentile(values, 99), percentile(values, 100));
    }

    String toJson(LoadConfig config) {
        String errors = errors().entrySet().stream()
                .map(e -> "\"" + e.getKey().replace("\\", "\\\\").replace("\"", "\\\"") + "\":" + e.getValue())
                .collect(Collectors.joining(",", "{", "}"));
        double throughput = wallClockMs == 0 ? 0 : total() * 1000.0 / wallClockMs;
        return "{\n  \"gridUrl\":\"" + config.gridUrl + "\",\n  \"sessions\":" + total() + ",\n  \"concurrency\":" + config.concurrency
                + ",\n  \"fakeNodes\":" + config.fakeNodes + ",\n  \"failed\":" + failed()
                + ",\n  \"errorRate\":" + String.format("%.4f", errorRate()) + ",\n  \"wallClockMs\":" + wallClockMs
                + ",\n  \"sessionsPerSecond\":" + String.format("%.2f", throughput)
                + ",\n  \"createSessionMs\":" + stats(createLatencies()) + ",\n  \"commandMs\":" + stats(commandLatencies())
                + ",\n  \"deleteSessionMs\":" + stats(deleteLatencies()) + ",\n  \"errors\":" + errors + "\n}\n";
    }

    Path write(LoadConfig config) throws IOException {
        Path dir = Paths.get(config.reportDir);
        Files.createDirectories(dir);
        Path file = dir.resolve("summary.json");
        Files.writeString(file, toJson(config));
        return file;
    }
}
