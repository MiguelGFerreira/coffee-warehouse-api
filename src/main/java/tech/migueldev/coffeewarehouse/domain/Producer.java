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
 * Producer/supplier a coffee lot originates from.
 *
 * The business code is the identity of a producer: it is unique in the schema,
 * it is assigned at creation and it never changes. That is why equals/hashCode
 * are built on it instead of on the surrogate id, which is null until the entity
 * is flushed and therefore useless inside a Set.
 */
@Entity
@Table(name = "producer")
public class Producer extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 100)
    private String city;

    // state is CHAR(2) in the schema, not VARCHAR: without this ddl-auto: validate
    // rejects the mapping at startup.
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 2)
    private String state;

    protected Producer() {
        // required by JPA
    }

    public Producer(String code, String name, String city, String state) {
        this.code = normalizeCode(code);
        this.name = name;
        this.city = city;
        this.state = normalizeState(state);
    }

    /**
     * Everything except the code, which is immutable once assigned.
     */
    public void updateDetails(String name, String city, String state) {
        this.name = name;
        this.city = city;
        this.state = normalizeState(state);
    }

    /**
     * Codes are compared and stored uppercase, otherwise "cop-001" and "COP-001"
     * would both get past the unique constraint and mean the same producer.
     */
    private static String normalizeCode(String code) {
        return code == null ? null : code.trim().toUpperCase();
    }

    /**
     * The schema enforces CHECK (state ~ '^[A-Z]{2}$'). Normalizing here turns a
     * lowercase input into a valid row instead of a constraint violation.
     */
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

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Producer producer && Objects.equals(code, producer.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code);
    }

    @Override
    public String toString() {
        return "Producer[code=%s, name=%s]".formatted(code, name);
    }
}
