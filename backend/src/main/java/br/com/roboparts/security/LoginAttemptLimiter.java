package br.com.roboparts.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Reserves attempts before password hashing so concurrent requests cannot bypass the limit. */
@Component
public class LoginAttemptLimiter {
    private final Clock clock;
    private final int attempts, capacity;
    private final long windowMillis;
    private final AttemptWindowStore shared;
    private final Map<String, Window> windows = new HashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    public LoginAttemptLimiter(Clock clock, @Value("${app.auth.account-limit.attempts:5}") int attempts,
            @Value("${app.auth.account-limit.seconds:900}") int seconds,
            @Value("${app.auth.account-limit.capacity:4096}") int capacity,
            org.springframework.beans.factory.ObjectProvider<AttemptWindowStore> stores) {
        this(clock,attempts,seconds,capacity,stores.getIfAvailable());
    }
    public LoginAttemptLimiter(Clock clock,int attempts,int seconds,int capacity) {
        this(clock,attempts,seconds,capacity,(AttemptWindowStore)null);
    }
    public LoginAttemptLimiter(Clock clock,int attempts,int seconds,int capacity,AttemptWindowStore shared) {
        if (attempts < 1 || attempts > 10000 || seconds < 1 || seconds > 3600 || capacity < 1 || capacity > 100000)
            throw new IllegalArgumentException("Configuração inválida do limite por conta.");
        this.clock = clock; this.attempts = attempts; this.capacity = capacity; this.windowMillis = seconds * 1000L;
        this.shared = shared;
    }
    public synchronized Ticket acquire(String email) {
        if(shared!=null) {
            String hash=key(email);var result=shared.acquire("account",hash,attempts,windowMillis,capacity);
            return new Ticket(hash,result.generation(),result.allowed(),result.retryAfterSeconds());
        }
        long now = clock.millis();
        windows.entrySet().removeIf(entry -> entry.getValue().expires <= now);
        String key = key(email);
        Window window = windows.get(key);
        if (window == null) {
            if (windows.size() >= capacity) return new Ticket(key, null, false, 60);
            window = new Window(now + windowMillis); windows.put(key, window);
        }
        if (window.used >= attempts) return new Ticket(key, null, false, Math.max(1, (window.expires - now + 999) / 1000));
        window.used++;
        return new Ticket(key, window.generation, true, 0);
    }
    public synchronized void success(Ticket ticket) {
        if(shared!=null){shared.success("account",ticket.key,ticket.generation);return;}
        Window current = windows.get(ticket.key);
        if (current != null && current.generation.equals(ticket.generation)) windows.remove(ticket.key);
    }
    static String key(String email) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(email.strip().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 indisponível."); }
    }
    public record Ticket(String key, UUID generation, boolean allowed, long retryAfterSeconds) {
        @Override public String toString() { return "LoginAttemptTicket[identidade omitida]"; }
    }
    private static class Window {
        final long expires; final UUID generation = UUID.randomUUID(); int used;
        Window(long expires) { this.expires = expires; }
    }
}
