package tech.migueldev.coffeewarehouse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * One entry in the append-only movement ledger.
 *
 * Deliberately not an {@link AuditableEntity}: there is no updated_at and no
 * version, because a row here is written once and never touched again. Every
 * field is immutable and there is no setter to break that.
 *
 * The three factory methods are the only way to build one, which is what keeps
 * an INBOUND with a source position -- or any other impossible shape -- from
 * being expressible in Java at all.
 */
@Entity
@Table(name = "stock_movement")
public class StockMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private MovementType type;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lot_id", nullable = false, updatable = false)
    private Lot lot;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_position_id", updatable = false)
    private StoragePosition sourcePosition;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "target_position_id", updatable = false)
    private StoragePosition targetPosition;

    @Column(nullable = false, updatable = false, precision = 12, scale = 3)
    private BigDecimal weightKg;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime occurredAt;

    @Column(updatable = false, length = 200)
    private String reason;

    @Column(nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected StockMovement() {
        // required by JPA
    }

    private StockMovement(MovementType type, Lot lot, StoragePosition sourcePosition,
                          StoragePosition targetPosition, BigDecimal weightKg,
                          OffsetDateTime occurredAt, String reason) {
        this.type = type;
        this.lot = lot;
        this.sourcePosition = sourcePosition;
        this.targetPosition = targetPosition;
        this.weightKg = weightKg;
        this.occurredAt = occurredAt == null ? OffsetDateTime.now() : occurredAt;
        this.reason = reason;
    }

    public static StockMovement inbound(Lot lot, StoragePosition target, BigDecimal weightKg,
                                        OffsetDateTime occurredAt, String reason) {
        return new StockMovement(MovementType.INBOUND, lot, null, target, weightKg, occurredAt, reason);
    }

    public static StockMovement transfer(Lot lot, StoragePosition source, StoragePosition target,
                                         BigDecimal weightKg, OffsetDateTime occurredAt, String reason) {
        return new StockMovement(MovementType.TRANSFER, lot, source, target, weightKg, occurredAt, reason);
    }

    public static StockMovement outbound(Lot lot, StoragePosition source, BigDecimal weightKg,
                                         OffsetDateTime occurredAt, String reason) {
        return new StockMovement(MovementType.OUTBOUND, lot, source, null, weightKg, occurredAt, reason);
    }

    @PrePersist
    void onInsert() {
        this.createdAt = OffsetDateTime.now();
    }

    public Long getId() {
        return id;
    }

    public MovementType getType() {
        return type;
    }

    public Lot getLot() {
        return lot;
    }

    public StoragePosition getSourcePosition() {
        return sourcePosition;
    }

    public StoragePosition getTargetPosition() {
        return targetPosition;
    }

    public BigDecimal getWeightKg() {
        return weightKg;
    }

    public OffsetDateTime getOccurredAt() {
        return occurredAt;
    }

    public String getReason() {
        return reason;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    @Override
    public String toString() {
        return "StockMovement[type=%s, lot=%s, weightKg=%s]".formatted(type, lot.getCode(), weightKg);
    }
}
