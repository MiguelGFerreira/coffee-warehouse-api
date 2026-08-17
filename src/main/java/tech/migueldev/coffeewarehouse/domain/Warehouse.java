package tech.migueldev.coffeewarehouse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Objects;

/**
 * Warehouse that holds storage positions.
 *
 * A warehouse is never deleted: storage positions reference it and, from Phase 3
 * on, so does the movement ledger. Taking one out of service is a state change
 * (active = false), not a removal.
 */
@Entity
@Table(name = "warehouse")
public class Warehouse extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 10)
    private String code;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 100)
    private String city;

    // state is CHAR(2) in the schema, not VARCHAR: without this ddl-auto: validate
    // rejects the mapping at startup.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 2)
    private String state;

    @Column(nullable = false)
    private boolean active;

    protected Warehouse() {
        // required by JPA
    }

    public Warehouse(String code, String name, String city, String state) {
        this.code = normalizeCode(code);
        this.name = name;
        this.city = city;
        this.state = normalizeState(state);
        this.active = true;
    }

    /**
     * Everything except the code, which is immutable once assigned, and the
     * active flag, which moves through activate()/deactivate().
     */
    public void updateDetails(String name, String city, String state) {
        this.name = name;
        this.city = city;
        this.state = normalizeState(state);
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    private static String normalizeCode(String code) {
        return code == null ? null : code.trim().toUpperCase();
    }

    private static String normalizeState(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        return state.trim().toUpperCase();
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getCity() {
        return city;
    }

    public String getState() {
        return state;
    }

    public boolean isActive() {
        return active;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Warehouse warehouse && Objects.equals(code, warehouse.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code);
    }

    @Override
    public String toString() {
        return "Warehouse[code=%s, name=%s]".formatted(code, name);
    }
}
