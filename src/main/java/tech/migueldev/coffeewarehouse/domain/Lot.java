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
