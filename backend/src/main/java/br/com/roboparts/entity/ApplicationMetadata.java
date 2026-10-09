package br.com.roboparts.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "application_metadata", schema = "roboparts")
public class ApplicationMetadata {
    @Id
    private Short id;

    @Column(name = "application_name", nullable = false, length = 80)
    private String applicationName;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ApplicationMetadata() { }

    public Short getId() { return id; }
    public String getApplicationName() { return applicationName; }
    public Instant getCreatedAt() { return createdAt; }
}
