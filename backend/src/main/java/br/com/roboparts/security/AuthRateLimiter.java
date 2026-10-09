package br.com.roboparts.security;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

// A bounded, process-local fixed window. Client identity comes only from the socket address.
public final class AuthRateLimiter {
    private final Clock clock;
    private final int requests;
    private final long windowMillis;
    private final int capacity;
    private final Map<String, Window> windows = new HashMap<>();

    public AuthRateLimiter(int requests, Duration window, int capacity) {
        this(Clock.systemUTC(), requests, window, capacity);
    }

    AuthRateLimiter(Clock clock, int requests, Duration window, int capacity) {
        if (requests < 1 || requests > 10000 || window.isNegative() || window.toMillis() < 1
                || window.compareTo(Duration.ofHours(1)) > 0 || capacity < 1 || capacity > 100000) {
            throw new IllegalArgumentException("Configuração inválida do limite de autenticação.");
        }
        this.clock = clock;
        this.requests = requests;
        this.windowMillis = window.toMillis();
        this.capacity = capacity;
    }

    public synchronized Decision accept(String clientAddress) {
        long now = clock.millis();
        windows.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
        Window window = windows.get(clientAddress);
        if (window == null) {
            if (windows.size() >= capacity) {
                long earliest = windows.values().stream().mapToLong(value -> value.expiresAt).min().orElse(now + windowMillis);
                return new Decision(false, remainingSeconds(now, earliest));
            }
            windows.put(clientAddress, new Window(now + windowMillis, 1));
            return new Decision(true, 0);
        }
        if (window.used >= requests) return new Decision(false, remainingSeconds(now, window.expiresAt));
        window.used++;
        return new Decision(true, 0);
    }

    synchronized int trackedClientCount() { return windows.size(); }
    private static long remainingSeconds(long now, long expiresAt) { return Math.max(1, (expiresAt - now + 999) / 1000); }
    public record Decision(boolean allowed, long retryAfterSeconds) { }
    private static final class Window {
        private final long expiresAt;
        private int used;
        private Window(long expiresAt, int used) { this.expiresAt = expiresAt; this.used = used; }
    }
}
