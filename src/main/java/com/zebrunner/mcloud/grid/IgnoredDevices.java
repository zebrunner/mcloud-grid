package com.zebrunner.mcloud.grid;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Devices temporarily excluded from automation (failed health checks, reserved in STF by another user, ...) with the reason.
 */
public final class IgnoredDevices {
    private static final Map<String, Entry> DEVICES = new ConcurrentHashMap<>();

    private IgnoredDevices() {
        //hide
    }

    /**
     * Why and until when a device is ignored.
     */
    public static final class Entry {
        private final Instant until;
        private final String reason;

        Entry(Instant until, String reason) {
            this.until = until;
            this.reason = reason;
        }

        public Instant getUntil() {
            return until;
        }

        public String getReason() {
            return reason;
        }

        public long secondsLeft() {
            return Math.max(0, Duration.between(Instant.now(), until).toSeconds());
        }
    }

    public static void ignore(String udid, Duration duration, String reason) {
        DEVICES.put(udid, new Entry(Instant.now().plus(duration), reason));
    }

    /**
     * @return the active entry of the device; an expired one is removed
     */
    public static Optional<Entry> get(String udid) {
        Entry entry = DEVICES.get(udid);
        if (entry == null) {
            return Optional.empty();
        }
        if (!Instant.now().isBefore(entry.until)) {
            DEVICES.remove(udid, entry);
            return Optional.empty();
        }
        return Optional.of(entry);
    }

    public static boolean isIgnored(String udid) {
        return get(udid).isPresent();
    }

    /**
     * @return active entries by udid
     */
    public static Map<String, Entry> snapshot() {
        Map<String, Entry> active = new HashMap<>();
        for (String udid : DEVICES.keySet()) {
            get(udid).ifPresent(entry -> active.put(udid, entry));
        }
        return Collections.unmodifiableMap(active);
    }

    /**
     * Forgets all devices, for tests.
     */
    public static void clear() {
        DEVICES.clear();
    }
}
