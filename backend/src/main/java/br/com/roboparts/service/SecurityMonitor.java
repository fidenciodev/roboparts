package br.com.roboparts.service;

import br.com.roboparts.security.SecuritySecrets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SecurityMonitor {
    private static final Logger log = LoggerFactory.getLogger(SecurityMonitor.class);
    private final JdbcTemplate jdbc; private final SecuritySecrets secrets; private final Clock clock;
    private final AtomicLong lastCleanup = new AtomicLong();
    public SecurityMonitor(JdbcTemplate jdbc, SecuritySecrets secrets, Clock clock) { this.jdbc = jdbc; this.secrets = secrets; this.clock = clock; }
    public void record(String type, String email) {
        String account = secrets.fingerprint(email);
        try {
            Instant now = clock.instant();
            jdbc.update("INSERT INTO roboparts.security_events(id,event_type,account_key,created_at) VALUES (?,?,?,?)", UUID.randomUUID(), type, account, now);
            long previous = lastCleanup.get();
            if (now.getEpochSecond() - previous > 3600 && lastCleanup.compareAndSet(previous, now.getEpochSecond()))
                jdbc.update("DELETE FROM roboparts.security_events WHERE created_at<?", now.minus(Duration.ofDays(30)));
        } catch (org.springframework.dao.DataAccessException exception) { log.warn("Não foi possível persistir um evento de segurança; detalhes omitidos."); }
        log.warn("security_event={} account={}", type, account.substring(0, 12));
    }
    public List<Summary> summary() {
        return jdbc.query("SELECT event_type, COUNT(*) AS total FROM roboparts.security_events WHERE created_at>=? GROUP BY event_type ORDER BY event_type", (row,index)->new Summary(row.getString("event_type"),row.getLong("total")), clock.instant().minus(Duration.ofHours(24)));
    }
    public List<Recent> recent() {
        return jdbc.query("SELECT event_type, SUBSTRING(account_key,1,12) AS account, created_at FROM roboparts.security_events ORDER BY created_at DESC LIMIT 100", (row,index)->new Recent(row.getString("event_type"),row.getString("account"),row.getTimestamp("created_at").toInstant()));
    }
    public record Summary(String event,long total) { }
    public record Recent(String event,String account,Instant occurred_at) { }
}
