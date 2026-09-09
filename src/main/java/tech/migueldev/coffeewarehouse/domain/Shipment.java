package tech.migueldev.coffeewarehouse.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import tech.migueldev.coffeewarehouse.api.exception.EmptyShipmentException;
import tech.migueldev.coffeewarehouse.api.exception.ShipmentNotEditableException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A dispatch composed from several lots.
 *
 * This is the aggregate root over its items: they are created, revised and
 * removed only through here, because the rule that governs all three -- the
 * composition is open only while the shipment is DRAFT -- is a property of the
 * shipment, not of any one line.
 *
 * <h2>The blend is derived, then frozen</h2>
 *
 * While DRAFT, {@link #blend()} computes from the items every time it is asked.
 * At confirmation the result is copied onto the row and never recomputed.
 *
 * That is not the cached-balance mistake rule 1 exists to prevent. A lot's
 * moisture is revisable -- {@code updateClassification} is exactly for that --
 * so a confirmed shipment recomputed from today's lot data would report a blend
 * that was never shipped. The snapshot is a historical fact frozen at a point in
 * time, the same reason a movement records {@code occurred_at} separately from
 * when it was written. Derive while it can still change; freeze when it becomes
 * history.
 */
@Entity
@Table(name = "shipment")
public class Shipment extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 30)
    private String code;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ShipmentStatus status;

    @Column(nullable = false, length = 150)
    private String destination;

    private LocalDate scheduledFor;

    private OffsetDateTime confirmedAt;

    @Column(precision = 5, scale = 2)
    private BigDecimal blendMoisturePercent;

    @Column(precision = 12, scale = 3)
    private BigDecimal blendWeightKg;

    @Column(precision = 12, scale = 3)
    private BigDecimal blendMoistureBasisKg;

    /**
     * Cascaded and orphan-removed: an item has no life outside its shipment, so
     * removing it from this list is what deletes it.
     */
    @OneToMany(mappedBy = "shipment", cascade = CascadeType.ALL,
               orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ShipmentItem> items = new ArrayList<>();

    protected Shipment() {
        // required by JPA
    }

    public Shipment(String code, String destination, LocalDate scheduledFor) {
        this.code = normalizeCode(code);
        this.destination = destination == null ? null : destination.trim();
        this.scheduledFor = scheduledFor;
        this.status = ShipmentStatus.DRAFT;
    }

    /**
     * Adds a line, or adds to the one already covering this lot and position.
     *
     * Merging rather than appending keeps the list in step with the unique
     * constraint on (shipment, lot, position): a second call for the same pair
     * is the same instruction for more weight, not a second instruction.
     */
    public ShipmentItem addItem(Lot lot, StoragePosition sourcePosition, BigDecimal weightKg) {
        ensureEditable();

        Optional<ShipmentItem> existing = findItem(lot, sourcePosition);
        if (existing.isPresent()) {
            ShipmentItem item = existing.get();
            item.changeWeight(item.getWeightKg().add(weightKg));
            return item;
        }

        ShipmentItem item = new ShipmentItem(this, lot, sourcePosition, weightKg);
        items.add(item);
        return item;
    }

    public void changeItemWeight(Long itemId, BigDecimal weightKg) {
        ensureEditable();
        itemById(itemId).changeWeight(weightKg);
    }

    public void removeItem(Long itemId) {
        ensureEditable();
        items.remove(itemById(itemId));
    }

    /**
     * Freezes the blend and closes the shipment.
     *
     * The blend is handed in rather than computed here: confirmation is the one
     * moment the number has to be the one the caller already validated the stock
     * against, and recomputing it would open a window for the two to disagree.
     */
    public void confirm(Blend blend) {
        ensureEditable();
        if (items.isEmpty()) {
            throw new EmptyShipmentException(
                    "Shipment %s has no items and cannot be confirmed".formatted(code));
        }

        this.blendWeightKg = blend.totalWeightKg();
        this.blendMoisturePercent = blend.moisturePercent();
        this.blendMoistureBasisKg = blend.moistureBasisKg();
        this.confirmedAt = OffsetDateTime.now();
        this.status = ShipmentStatus.CONFIRMED;
    }

    public void cancel() {
        ensureEditable();
        this.status = ShipmentStatus.CANCELLED;
    }

    /**
     * The blend as it stands: computed from the items while DRAFT, read back
     * from the snapshot once confirmed.
     *
     * <p><b>Only the numbers are frozen.</b> The weight, the moisture average
     * and its basis come off the row for a confirmed shipment; the categorical
     * composition is recomputed from the lots either way. That asymmetry is
     * deliberate and worth stating rather than hiding:
     *
     * <ul>
     *   <li>the moisture average is a figure recorded <em>at dispatch</em>, the
     *       kind of number a contract quotes, so it has to stay what it was on
     *       the day;</li>
     *   <li>the screen, defect and cup shares are a descriptive view of how the
     *       lots are graded, and a re-grade is new information about the same
     *       coffee rather than a change to what was sent.</li>
     * </ul>
     *
     * The honest cost: re-grading a lot after dispatch does move the reported
     * composition of a confirmed shipment, while its moisture stays put.
     * Freezing the composition too would mean a JSONB column or a child table
     * for the shares, which is more schema than this phase needs -- but it is
     * the reason the item weights are immutable after confirmation, so the only
     * thing that can move is the label.
     *
     * A cancelled shipment falls to the DRAFT branch and recomputes, which is
     * harmless -- nothing was ever frozen for it because nothing ever shipped.
     */
    public Blend blend() {
        Blend derived = Blend.of(items);
        if (status != ShipmentStatus.CONFIRMED) {
            return derived;
        }
        return new Blend(blendWeightKg, blendMoisturePercent, blendMoistureBasisKg,
                derived.screenSizes(), derived.defectTypes(), derived.cupQualities());
    }

    /**
     * The composition is open only while the shipment is DRAFT. Stated here
     * because it governs every mutation, rather than repeated at each one.
     */
    public void ensureEditable() {
        if (status.isTerminal()) {
            throw new ShipmentNotEditableException(
                    "Shipment %s is %s and can no longer be changed".formatted(code, status));
        }
    }

    public Optional<ShipmentItem> findItem(Lot lot, StoragePosition sourcePosition) {
        return items.stream()
                .filter(item -> item.getLot().equals(lot)
                        && item.getSourcePosition().equals(sourcePosition))
                .findFirst();
    }

    private ShipmentItem itemById(Long itemId) {
        return items.stream()
                .filter(item -> Objects.equals(item.getId(), itemId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Item %s does not belong to shipment %s".formatted(itemId, code)));
    }

    private static String normalizeCode(String code) {
        return code == null ? null : code.trim().toUpperCase();
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public ShipmentStatus getStatus() {
        return status;
    }

    public String getDestination() {
        return destination;
    }

    public LocalDate getScheduledFor() {
        return scheduledFor;
    }

    public OffsetDateTime getConfirmedAt() {
        return confirmedAt;
    }

    public List<ShipmentItem> getItems() {
        return Collections.unmodifiableList(items);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Shipment shipment && Objects.equals(code, shipment.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code);
    }

    @Override
    public String toString() {
        return "Shipment[code=%s, status=%s]".formatted(code, status);
    }
}
