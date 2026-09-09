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
import jakarta.persistence.Table;

import tech.migueldev.coffeewarehouse.api.exception.LotNotMovableException;
import tech.migueldev.coffeewarehouse.api.exception.LotWeightExceededException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * A lot of coffee received from a producer.
 *
 * Weight, crop year, producer and receiving date are fixed at creation. Once the
 * ledger exists the weight of a lot is the history of its movements, not an
 * editable field, so allowing it to be edited here would create a second source
 * of truth. What stays editable is the classification, which is genuinely
 * revised as samples are graded.
 */
@Entity
@Table(name = "lot")
public class Lot extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false, length = 30)
    private String code;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "producer_id", nullable = false, updatable = false)
    private Producer producer;

    @Column(nullable = false, updatable = false)
    private Integer cropYear;

    @Column(nullable = false, updatable = false, precision = 12, scale = 3)
    private BigDecimal netWeightKg;

    private Integer bags;

    @Column(precision = 5, scale = 2)
    private BigDecimal moisturePercent;

    @Column(length = 10)
    private String screenSize;

    @Column(length = 10)
    private String defectType;

    @Column(length = 30)
    private String cupQuality;

    @Column(nullable = false, updatable = false)
    private LocalDate receivedOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private LotStatus status;

    protected Lot() {
        // required by JPA
    }

    public Lot(String code, Producer producer, Integer cropYear, BigDecimal netWeightKg,
               LocalDate receivedOn) {
        this.code = normalizeCode(code);
        this.producer = producer;
        this.cropYear = cropYear;
        this.netWeightKg = netWeightKg;
        this.receivedOn = receivedOn;
        this.status = LotStatus.AWAITING_ALLOCATION;
    }

    /**
     * Classification is the revisable part of a lot: samples get re-graded, the
     * bag count gets corrected. Status is not here on purpose -- it moves with
     * the ledger, in Phase 3, never by direct edit.
     */
    public void updateClassification(Integer bags, BigDecimal moisturePercent, String screenSize,
                                     String defectType, String cupQuality) {
        this.bags = bags;
        this.moisturePercent = moisturePercent;
        this.screenSize = normalizeOptional(screenSize);
        this.defectType = normalizeOptional(defectType);
        this.cupQuality = normalizeOptional(cupQuality);
    }

    /**
     * A SHIPPED lot is terminal: it has left the warehouse and its history is
     * closed. The rule lives here rather than in the service because it is a
     * property of the lot, not of the operation being attempted.
     */
    public void ensureMovable() {
        if (status == LotStatus.SHIPPED) {
            throw new LotNotMovableException(
                    "Lot %s is %s and accepts no movement".formatted(code, status));
        }
    }

    /**
     * A lot cannot have more of it put away than the producer delivered.
     *
     * The mirror of {@code StoragePosition.ensureFits}: same shape, different
     * bar. A position is capped by what it can physically hold; a lot is capped
     * by what physically exists. Both take the aggregation as a parameter rather
     * than reaching for a repository from inside the entity.
     *
     * What is passed in is everything ever received, not the current balance.
     * A lot that shipped out entirely has a balance of zero, but that coffee is
     * gone -- it does not become receivable again. Coffee genuinely coming back
     * would be a business event this model does not have, and letting it in
     * through a slack ceiling would be the wrong way to acquire one.
     */
    public void ensureInboundFits(BigDecimal alreadyReceived, BigDecimal incomingWeightKg) {
        BigDecimal resulting = alreadyReceived.add(incomingWeightKg);
        if (resulting.compareTo(netWeightKg) > 0) {
            throw new LotWeightExceededException(
                    "Lot %s has %s kg of its %s kg already received; %s kg more would exceed it"
                            .formatted(code, alreadyReceived, netWeightKg, incomingWeightKg));
        }
    }

    /**
     * The one transition Phase 3 owns: the first inbound puts a lot away.
     * Idempotent, so a second inbound does not care. RESERVED and SHIPPED are
     * driven by the shipment, not by the ledger.
     */
    public void markStored() {
        if (status == LotStatus.AWAITING_ALLOCATION) {
            this.status = LotStatus.STORED;
        }
    }

    /**
     * The lot is committed to a draft shipment.
     *
     * The status is a coarse label -- "is any of this lot spoken for?" -- and
     * nothing more. It is not the reservation itself: <em>how much</em> is
     * committed is always summed from the draft shipment items, never stored,
     * for the same reason occupancy is never stored. The label exists because
     * the lifecycle has a name for this state; the number exists because only
     * the number can be trusted.
     *
     * Kept in step by being recomputed at every transition rather than
     * incremented, which is what stops it drifting from the items it describes.
     */
    public void markReserved() {
        if (status == LotStatus.STORED) {
            this.status = LotStatus.RESERVED;
        }
    }

    /**
     * The last draft shipment holding this lot let it go. The caller checks
     * that no other one still does -- this only knows how to move the label.
     */
    public void releaseReservation() {
        if (status == LotStatus.RESERVED) {
            this.status = LotStatus.STORED;
        }
    }

    /**
     * The lot has left the warehouse for good.
     *
     * Only correct once nothing of it remains stored: SHIPPED is terminal and
     * refuses every later movement, so marking a partially dispatched lot would
     * strand whatever is still on the floor. The caller checks the balance; the
     * rule that SHIPPED is a one-way door lives in {@link #ensureMovable()}.
     */
    public void markShipped() {
        this.status = LotStatus.SHIPPED;
    }

    /**
     * Dispatched, but not all of it: whatever is left is ordinary stored stock
     * again, free to be picked for another shipment.
     */
    public void returnToStored() {
        if (status == LotStatus.RESERVED) {
            this.status = LotStatus.STORED;
        }
    }

    private static String normalizeCode(String code) {
        return code == null ? null : code.trim().toUpperCase();
    }

    private static String normalizeOptional(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase();
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public Producer getProducer() {
        return producer;
    }

    public Integer getCropYear() {
        return cropYear;
    }

    public BigDecimal getNetWeightKg() {
        return netWeightKg;
    }

    public Integer getBags() {
        return bags;
    }

    public BigDecimal getMoisturePercent() {
        return moisturePercent;
    }

    public String getScreenSize() {
        return screenSize;
    }

    public String getDefectType() {
        return defectType;
    }

    public String getCupQuality() {
        return cupQuality;
    }

    public LocalDate getReceivedOn() {
        return receivedOn;
    }

    public LotStatus getStatus() {
        return status;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof Lot lot && Objects.equals(code, lot.code);
    }

    @Override
    public int hashCode() {
        return Objects.hash(code);
    }

    @Override
    public String toString() {
        return "Lot[code=%s, status=%s]".formatted(code, status);
    }
}
