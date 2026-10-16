package br.com.roboparts.security;

import java.sql.Connection;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** Cloud-only infrastructure, installed by an explicit offline transaction. */
public final class CloudSchema {
    private CloudSchema() { }
    public static void install(Connection connection) throws Exception {
        try (var query=connection.createStatement(); var row=query.executeQuery("SELECT to_regclass('roboparts.cloud_state') IS NOT NULL")) {
            row.next();
            if(row.getBoolean(1)) { validate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true)); return; }
        }
        try (var query=connection.createStatement(); var row=query.executeQuery("""
                SELECT count(*) FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace
                WHERE n.nspname='roboparts' AND c.relname IN ('web_sessions','web_sessions_attributes','auth_attempt_locks','auth_attempt_windows')
                """)) {
            row.next(); if(row.getInt(1)!=0) throw new IllegalStateException("Cloud schema is incomplete; manual inspection required.");
        }
        ScriptUtils.executeSqlScript(connection,new ClassPathResource("db/cloud/schema-v1.sql"));
        validate(new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection,true));
    }
    public static void validate(DataSource source) {
        JdbcTemplate jdbc=new JdbcTemplate(source);
        Integer version=jdbc.queryForObject("SELECT version FROM roboparts.cloud_state WHERE id=1",Integer.class);
        if(version==null||version!=1)throw new IllegalStateException("Execute a preparacao offline do Neon antes de publicar.");
        jdbc.queryForList("SELECT primary_id,session_id,creation_time,last_access_time,max_inactive_interval,expiry_time,principal_name FROM roboparts.web_sessions WHERE 1=0");
        jdbc.queryForList("SELECT session_primary_id,attribute_name,attribute_bytes FROM roboparts.web_sessions_attributes WHERE 1=0");
        jdbc.queryForList("SELECT scope_id,identity_hash,generation,expires_at,used FROM roboparts.auth_attempt_windows WHERE 1=0");
        if(jdbc.queryForObject("SELECT count(*) FROM roboparts.auth_attempt_locks WHERE scope_id IN ('ip','account')",Integer.class)!=2)
            throw new IllegalStateException("Shared authentication locks missing.");
    }
}
