package br.com.roboparts.security;

import java.util.UUID;

/** Optional shared storage; the local application keeps its bounded in-memory limits. */
public interface AttemptWindowStore {
    Reservation acquire(String scope, String identityHash, int limit, long windowMillis, int capacity);
    void success(String scope, String identityHash, UUID generation);
    record Reservation(UUID generation, boolean allowed, long retryAfterSeconds) { }
}
