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
 * A physical address inside a warehouse: warehouse > aisle > bay > level.
 *
 * The composite code is derived, never supplied by the caller. Letting a client
 * send both the address and its code would allow the two to disagree, and the
 * code is the identity of the position.
 *
 * Occupancy is deliberately absent. How full this position is comes from
 * aggregating the movement ledger, not from a column that can drift.
 */
@Entity
@Table(name = "storage_position")
public class StoragePosition extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "warehouse_id", nullable = false, updatable = false)
    private Warehouse warehouse;

    @Column(nullable = false, updatable = false, length = 10)
    private String aisle;

    @Column(nullable = false, updatable = false, length = 10)
    private String bay;

    @Column(nullable = false, updatable = false, length = 10)
    private String level;

    @Column(nullable = false, updatable = false, length = 40)
    private String code;

    @Column(nullable = false, precision = 12, scale = 3)
    private BigDecimal capacityKg;

    @Column(nullable = false)
    private boolean active;

    protected StoragePosition() {
        // required by JPA
    }

    public StoragePosition(Warehouse warehouse, String aisle, String bay, String level,
                           BigDecimal capacityKg) {
        this.warehouse = warehouse;
        this.aisle = normalizeComponent(aisle);
        this.bay = normalizeComponent(bay);
        this.level = normalizeComponent(level);
        this.code = buildCode(warehouse.getCode(), this.aisle, this.bay, this.level);
        this.capacityKg = capacityKg;
        this.active = true;
    }

    /**
     * WH1-A03-B12-L02: warehouse code, then each address component behind the
     * letter that names it.
     */
    public static String buildCode(String warehouseCode, String aisle, String bay, String level) {
        return "%s-A%s-B%s-L%s".formatted(
                warehouseCode,
                normalizeComponent(aisle),
                normalizeComponent(bay),
                normalizeComponent(level));
    }

    /**
     * Numeric components are padded to two digits so aisle "3" and aisle "03"
     * are the same address. The stored value is the padded one, which keeps the
     * unique constraint on (warehouse, aisle, bay, level) and the unique
     * constraint on the code agreeing with each other.
     */
    private static String normalizeComponent(String component) {
        String normalized = component.trim().toUpperCase();
        boolean numeric = !normalized.isEmpty() && normalized.chars().allMatch(Character::isDigit);
        return numeric && normalized.length() == 1 ? "0" + normalized : normalized;
    }

    /**
     * Capacity is the one thing that can change: a position can be re-rated.
     * Once the ledger exists this will have to refuse a capacity below what is
     * already stored here -- that check belongs to Phase 3, with the balance.
     */
    public void changeCapacity(BigDecimal capacityKg) {
        this.capacityKg = capacityKg;
    }

    public void activate() {
        this.active = true;
    }

    public void deactivate() {
        this.active = false;
    }

    public Long getId() {
        return id;
    }

    public Warehouse getWarehouse() {
        return warehouse;
    }

    public String getAisle() {
        return aisle;
    }

    public String getBay() {
        return bay;
    }

    public String getLevel() {
        return level;
    }

    public String getCode() {
        return code;
    }

    public BigDecimal getCapacityKg() {
        return capacityKg;
    }

    public boolean isActive() {
        return active;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof StoragePosition position && Objects.equals(code, position.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code);
    }

    @Override
    public String toString() {
        return "StoragePosition[code=%s, capacityKg=%s]".formatted(code, capacityKg);
    }
}
