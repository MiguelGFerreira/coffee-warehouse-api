package tech.migueldev.coffeewarehouse.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * What a set of shipment items adds up to: the weight, the moisture averaged
 * weighted by weight, and how the categorical classification is composed.
 *
 * <h2>Why moisture is weighted and not averaged</h2>
 *
 * 12,000 kg at 11.50% blended with 6,000 kg at 12.80% is not 12.15%. The
 * simple average treats the small parcel as equal to the large one; the
 * weighted average -- {@code Σ(moisture × weight) / Σ(weight)} -- gives 11.93%,
 * which is the moisture of the coffee that actually leaves. This is the whole
 * point of the calculation, so it is worth stating that the wrong answer is
 * only two characters away in the code.
 *
 * <h2>Why classification is composed and not averaged</h2>
 *
 * Screen size, defect type and cup quality are categorical. There is no average
 * of {@code HARD} and {@code SOFT}. Each is reported as the share of the total
 * weight sitting behind every distinct value -- which, for screen size, is not a
 * workaround but the trade standard: a sieve analysis reports the mass
 * percentage retained on each screen.
 *
 * <h2>The two denominators, and why they differ</h2>
 *
 * Moisture divides by the weight that actually has a reading. Averaging over
 * the total instead would treat an ungraded lot as 0% moisture and drag the
 * result down -- a wrong number, not merely an incomplete one. {@link
 * #moistureBasisKg()} reports that weight so the average cannot quietly
 * overstate its own coverage.
 *
 * The categorical shares divide by the <em>total</em> weight. Nothing is
 * distorted by doing so: a lot with no screen size simply contributes to no
 * bucket, and the shares then sum to less than 100%, with the gap being exactly
 * the weight nobody graded. One denominator for every share keeps the numbers
 * checkable by hand.
 */
public record Blend(
        BigDecimal totalWeightKg,
        BigDecimal moisturePercent,
        BigDecimal moistureBasisKg,
        List<WeightedShare> screenSizes,
        List<WeightedShare> defectTypes,
        List<WeightedShare> cupQualities
) {

    /** Percentages carry two decimals, matching {@code NUMERIC(5,2)} in the schema. */
    private static final int PERCENT_SCALE = 2;

    /** Weights carry three, matching {@code NUMERIC(12,3)}. */
    private static final int WEIGHT_SCALE = 3;

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    /**
     * One distinct classification value and the weight behind it.
     *
     * @param sharePercent share of the shipment's total weight, not of the
     *                     graded weight -- see the class comment
     */
    public record WeightedShare(String value, BigDecimal weightKg, BigDecimal sharePercent) {
    }

    /**
     * An empty blend. Confirmation refuses to reach this, but a DRAFT shipment
     * with no items yet is a perfectly ordinary thing to read.
     */
    public static Blend empty() {
        BigDecimal zero = BigDecimal.ZERO.setScale(WEIGHT_SCALE);
        return new Blend(zero, null, zero, List.of(), List.of(), List.of());
    }

    public static Blend of(Collection<ShipmentItem> items) {
        if (items.isEmpty()) {
            return empty();
        }

        BigDecimal totalWeight = items.stream()
                .map(ShipmentItem::getWeightKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);

        return new Blend(
                totalWeight,
                weightedMoisture(items),
                moistureBasis(items),
                sharesOf(items, totalWeight, lot -> lot.getScreenSize()),
                sharesOf(items, totalWeight, lot -> lot.getDefectType()),
                sharesOf(items, totalWeight, lot -> lot.getCupQuality()));
    }

    /**
     * {@code Σ(moisture × weight) / Σ(weight)}, over the items whose lot has a
     * reading. Null when none of them does -- a blend of ungraded coffee has no
     * moisture to report, and returning zero would be a claim rather than an
     * absence.
     */
    private static BigDecimal weightedMoisture(Collection<ShipmentItem> items) {
        BigDecimal basis = moistureBasis(items);
        if (basis.signum() == 0) {
            return null;
        }

        BigDecimal weighted = items.stream()
                .filter(item -> item.getLot().getMoisturePercent() != null)
                .map(item -> item.getLot().getMoisturePercent().multiply(item.getWeightKg()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // The scale is explicit because divide() without one throws on a
        // non-terminating decimal, and 214800/18000 is exactly that case.
        return weighted.divide(basis, PERCENT_SCALE, RoundingMode.HALF_UP);
    }

    private static BigDecimal moistureBasis(Collection<ShipmentItem> items) {
        return items.stream()
                .filter(item -> item.getLot().getMoisturePercent() != null)
                .map(ShipmentItem::getWeightKg)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(WEIGHT_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * Groups the weight by one categorical attribute, heaviest share first.
     *
     * Items whose lot has no value for the attribute are skipped rather than
     * collected under a placeholder: an absent grade is not a grade, and giving
     * it a bucket would put it in the same list as the real ones.
     */
    private static List<WeightedShare> sharesOf(Collection<ShipmentItem> items,
                                                BigDecimal totalWeight,
                                                Function<Lot, String> attribute) {
        Map<String, BigDecimal> byValue = new LinkedHashMap<>();
        for (ShipmentItem item : items) {
            String value = attribute.apply(item.getLot());
            if (value != null) {
                byValue.merge(value, item.getWeightKg(), BigDecimal::add);
            }
        }

        return byValue.entrySet().stream()
                .map(entry -> new WeightedShare(
                        entry.getKey(),
                        entry.getValue().setScale(WEIGHT_SCALE, RoundingMode.HALF_UP),
                        entry.getValue()
                                .multiply(HUNDRED)
                                .divide(totalWeight, PERCENT_SCALE, RoundingMode.HALF_UP)))
                // Heaviest first, then alphabetically, so the output is stable
                // enough to assert on.
                .sorted(Comparator.comparing(WeightedShare::weightKg).reversed()
                        .thenComparing(WeightedShare::value))
                .toList();
    }
}
