package br.com.roboparts.security;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** A short database transaction serializes reservations across all web instances. */
public final class JdbcAttemptWindowStore implements AttemptWindowStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcAttemptWindowStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(manager);
        transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        transactions.setTimeout(10);
    }

    @Override public Reservation acquire(String scope, String hash, int limit, long windowMillis, int capacity) {
        validate(scope, hash);
        if (limit < 1 || capacity < 1 || windowMillis < 1) throw new IllegalArgumentException();
        return transactions.execute(status -> {
            // Lock a seeded row, including when a window does not exist yet.
            jdbc.queryForObject("SELECT scope_id FROM roboparts.auth_attempt_locks WHERE scope_id=? FOR UPDATE", String.class, scope);
            long now = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", (rs, row) -> rs.getTimestamp(1).toInstant().toEpochMilli());
            jdbc.update("DELETE FROM roboparts.auth_attempt_windows WHERE scope_id=? AND expires_at<=?", scope, now);
            var current = jdbc.query("SELECT generation,expires_at,used FROM roboparts.auth_attempt_windows WHERE scope_id=? AND identity_hash=?",
                    (rs, row) -> new Window(rs.getObject(1,UUID.class),rs.getLong(2),rs.getInt(3)), scope, hash);
            if (current.isEmpty()) {
                int tracked = jdbc.queryForObject("SELECT count(*) FROM roboparts.auth_attempt_windows WHERE scope_id=?", Integer.class, scope);
                if (tracked >= capacity) return new Reservation(null,false,Math.max(1,(windowMillis+999)/1000));
                UUID generation = UUID.randomUUID();
                jdbc.update("INSERT INTO roboparts.auth_attempt_windows(scope_id,identity_hash,generation,expires_at,used) VALUES (?,?,?,?,1)",scope,hash,generation,now+windowMillis);
                return new Reservation(generation,true,0);
            }
            Window window = current.getFirst();
            if (window.used >= limit) return new Reservation(null,false,Math.max(1,(window.expires-now+999)/1000));
            jdbc.update("UPDATE roboparts.auth_attempt_windows SET used=used+1 WHERE scope_id=? AND identity_hash=?",scope,hash);
            return new Reservation(window.generation,true,0);
        });
    }

    @Override public void success(String scope, String hash, UUID generation) {
        validate(scope, hash);
        if (generation == null) return;
        transactions.executeWithoutResult(status -> {
            jdbc.queryForObject("SELECT scope_id FROM roboparts.auth_attempt_locks WHERE scope_id=? FOR UPDATE",String.class,scope);
            jdbc.update("DELETE FROM roboparts.auth_attempt_windows WHERE scope_id=? AND identity_hash=? AND generation=?",scope,hash,generation);
        });
    }
    private static void validate(String scope, String hash) {
        if (!java.util.Set.of("ip","account").contains(scope) || !hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException();
    }
    private record Window(UUID generation,long expires,int used) { }
}
