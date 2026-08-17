package tech.migueldev.coffeewarehouse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;

import java.time.OffsetDateTime;

/**
 * Audit columns shared by every registry table: created_at, updated_at and the
 * optimistic lock version.
 *
 * The timestamps are written by JPA rather than left to the DEFAULT now() in the
 * schema. The database default only covers the insert, so updated_at would never
 * move on an update. The default stays in the schema as a safety net for rows
 * inserted by direct SQL (seeds, migrations).
 */
@MappedSuperclass
public abstract class AuditableEntity {

    @Column(nullable = false)
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    private OffsetDateTime updatedAt;

    @Version
    private Long version;

    @PrePersist
    void onInsert() {
        OffsetDateTime now = OffsetDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = OffsetDateTime.now();
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
