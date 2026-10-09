package com.zebrunner.mcloud.grid.load;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
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
    // requests the hub sent to the fake STF by endpoint
    private final Map<String, Long> stfRequests;

    LoadReport(List<SessionResult> results, long wallClockMs, Map<String, Long> stfRequests) {
        this.results = results;
        this.wallClockMs = wallClockMs;
        this.stfRequests = stfRequests;
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

    private static Map<String, Long> stats(List<Long> values) {
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("count", (long) values.size());
        stats.put("p50", percentile(values, 50));
        stats.put("p95", percentile(values, 95));
        stats.put("p99", percentile(values, 99));
        stats.put("max", percentile(values, 100));
        return stats;
    }

    String toJson(LoadConfig config) {
        double throughput = wallClockMs == 0 ? 0 : total() * 1000.0 / wallClockMs;
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("gridUrl", config.gridUrl);
        report.put("sessions", total());
        report.put("concurrency", config.concurrency);
        report.put("fakeNodes", config.fakeNodes);
        report.put("failed", failed());
        report.put("errorRate", errorRate());
        report.put("wallClockMs", wallClockMs);
        report.put("sessionsPerSecond", throughput);
        report.put("createSessionMs", stats(createLatencies()));
        report.put("commandMs", stats(commandLatencies()));
        report.put("deleteSessionMs", stats(deleteLatencies()));
        report.put("errors", errors());
        report.put("stfRequests", stfRequests);
        try {
            return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n";
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize load report", e);
        }
    }

    Path write(LoadConfig config) throws IOException {
        Path dir = Paths.get(config.reportDir);
        Files.createDirectories(dir);
        Path file = dir.resolve("summary.json");
        Files.writeString(file, toJson(config));
        return file;
    }
}
