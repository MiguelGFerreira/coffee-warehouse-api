package tech.migueldev.coffeewarehouse.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One line of a shipment: this much of this lot, out of this position.
 *
 * It is a picking instruction, not just a quantity. Naming the position here
 * rather than resolving it when the shipment is confirmed is what makes
 * confirmation deterministic -- a lot can sit in several positions, and the
 * ledger needs to be told which one the weight leaves from.
 *
 * The lot and the position are fixed at creation; only the weight is revisable,
 * and only while the shipment is still DRAFT. Changing which lot a line refers
 * to is a different instruction, so it is a different line.
 */
@Entity
@Table(name = "shipment_item")
public class ShipmentItem extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "shipment_id", nullable = false, updatable = false)
    private Shipment shipment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lot_id", nullable = false, updatable = false)
    private Lot lot;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "source_position_id", nullable = false, updatable = false)
    private StoragePosition sourcePosition;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal weightKg;

    protected ShipmentItem() {
        // required by JPA
    }

    ShipmentItem(Shipment shipment, Lot lot, StoragePosition sourcePosition, BigDecimal weightKg) {
        this.shipment = shipment;
        this.lot = lot;
        this.sourcePosition = sourcePosition;
        this.weightKg = weightKg;
    }

    /**
     * Package-private, and only reachable through the shipment, which is the
     * aggregate root that knows whether it is still editable.
     */
    void changeWeight(BigDecimal weightKg) {
        this.weightKg = weightKg;
    }

    public Long getId() {
        return id;
    }

    public Shipment getShipment() {
        return shipment;
    }

    public Lot getLot() {
        return lot;
    }

    public StoragePosition getSourcePosition() {
        return sourcePosition;
    }

    public BigDecimal getWeightKg() {
        return weightKg;
    }

    /**
     * Identity is the lot and the position it comes out of -- the line, within
     * its own shipment.
     *
     * The shipment is deliberately left out, even though the unique constraint
     * includes it. It is a lazy association, and comparing it would initialize
     * the parent proxy just to answer whether two children match, which throws
     * outside a session with {@code open-in-view: false}. Items are only ever
     * compared inside one shipment's own collection, where the parent is
     * constant and contributes nothing.
     */
    @Override
    public boolean equals(Object other) {
        return other instanceof ShipmentItem item
                && Objects.equals(lot, item.lot)
                && Objects.equals(sourcePosition, item.sourcePosition);
    }

    @Override
    public int hashCode() {
        return Objects.hash(lot, sourcePosition);
    }

    @Override
    public String toString() {
        return "ShipmentItem[lot=%s, from=%s, weightKg=%s]"
                .formatted(lot.getCode(), sourcePosition.getCode(), weightKg);
    }
}
