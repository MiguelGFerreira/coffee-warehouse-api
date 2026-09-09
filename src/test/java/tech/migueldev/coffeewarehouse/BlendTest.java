package tech.migueldev.coffeewarehouse;

import static org.assertj.core.api.Assertions.assertThat;

import tech.migueldev.coffeewarehouse.domain.Blend;
import tech.migueldev.coffeewarehouse.domain.Lot;
import tech.migueldev.coffeewarehouse.domain.Producer;
import tech.migueldev.coffeewarehouse.domain.Shipment;
import tech.migueldev.coffeewarehouse.domain.StoragePosition;
import tech.migueldev.coffeewarehouse.domain.Warehouse;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The blend arithmetic, in plain objects.
 *
 * No Spring and no database on purpose: this is a calculation over a handful of
 * values, and every number below can be checked with a pocket calculator. That
 * is the property the roadmap asks for, and it is what makes the test able to
 * catch a wrong formula rather than just a wrong wiring.
 */
class BlendTest {

    private final Producer producer =
            new Producer("COP-001", "Cooperativa Serra Alta", "Guaxupe", "MG");
    private final Warehouse warehouse =
            new Warehouse("WH1", "Armazem Central", "Guaxupe", "MG");
    private final StoragePosition positionA =
            new StoragePosition(warehouse, "01", "01", "01", new BigDecimal("60000.000"));
    private final StoragePosition positionB =
            new StoragePosition(warehouse, "02", "01", "01", new BigDecimal("60000.000"));

    private int nextLot = 1;

    private Lot lot(String moisture, String screen, String defect, String cup) {
        Lot lot = new Lot("LOT-%03d".formatted(nextLot++), producer, 2025,
                new BigDecimal("100000.000"), LocalDate.of(2025, 6, 10));
        lot.updateClassification(null,
                moisture == null ? null : new BigDecimal(moisture), screen, defect, cup);
        return lot;
    }

    private Shipment shipmentOf(Object... lotsAndWeights) {
        Shipment shipment = new Shipment("SHP-001", "Port of Santos", LocalDate.of(2025, 7, 1));
        for (int i = 0; i < lotsAndWeights.length; i += 2) {
            Lot lot = (Lot) lotsAndWeights[i];
            BigDecimal weight = new BigDecimal((String) lotsAndWeights[i + 1]);
            // Alternate the source position so the lines stay distinct even when
            // two of them carry the same lot.
            shipment.addItem(lot, i % 4 == 0 ? positionA : positionB, weight);
        }
        return shipment;
    }

    @Nested
    @DisplayName("moisture weighted by weight")
    class Moisture {

        /**
         * 12,000 kg at 11.50% and 6,000 kg at 12.80%.
         *
         *   (12000 x 11.50) + (6000 x 12.80)  =  138000 + 76800  =  214800
         *   214800 / 18000                    =  11.9333...      -> 11.93
         *
         * The simple average would be (11.50 + 12.80) / 2 = 12.15, which is the
         * number this test exists to rule out: it counts the 6-tonne parcel as
         * equal to the 12-tonne one.
         */
        @Test
        @DisplayName("weights the larger parcel more heavily than the smaller one")
        void weightsByWeightNotByCount() {
            Blend blend = shipmentOf(
                    lot("11.50", null, null, null), "12000.000",
                    lot("12.80", null, null, null), "6000.000").blend();

            assertThat(blend.totalWeightKg()).isEqualByComparingTo("18000.000");
            assertThat(blend.moisturePercent()).isEqualByComparingTo("11.93");
            assertThat(blend.moisturePercent()).isNotEqualByComparingTo("12.15");
            assertThat(blend.moistureBasisKg()).isEqualByComparingTo("18000.000");
        }

        /**
         * Equal weights are the one case where weighted and simple agree, which
         * is worth pinning: it proves the weighting is not accidentally
         * inverted, since an inverted formula would also pass the test above by
         * landing on the other side of the true value.
         */
        @Test
        @DisplayName("agrees with the simple average when the parcels are equal")
        void matchesTheSimpleAverageOnEqualWeights() {
            Blend blend = shipmentOf(
                    lot("11.00", null, null, null), "9000.000",
                    lot("13.00", null, null, null), "9000.000").blend();

            assertThat(blend.moisturePercent()).isEqualByComparingTo("12.00");
        }

        /**
         * 10,000 kg at 10.00%, 3,000 kg at 12.00% and 2,000 kg with no reading.
         *
         *   (10000 x 10) + (3000 x 12)  =  100000 + 36000  =  136000
         *   136000 / 13000              =  10.4615...      -> 10.46
         *
         * Dividing by the full 15,000 would give 9.07 -- the ungraded parcel
         * counted as if it were bone dry, which is a wrong number rather than an
         * incomplete one. The basis reports the 13,000 the average covers.
         */
        @Test
        @DisplayName("excludes lots with no reading from both sides of the ratio")
        void excludesUngradedLotsFromTheAverage() {
            Blend blend = shipmentOf(
                    lot("10.00", null, null, null), "10000.000",
                    lot("12.00", null, null, null), "3000.000",
                    lot(null, null, null, null), "2000.000").blend();

            assertThat(blend.totalWeightKg()).isEqualByComparingTo("15000.000");
            assertThat(blend.moisturePercent()).isEqualByComparingTo("10.46");
            assertThat(blend.moisturePercent()).isNotEqualByComparingTo("9.07");
            assertThat(blend.moistureBasisKg()).isEqualByComparingTo("13000.000");
        }

        @Test
        @DisplayName("reports no moisture at all when nothing in the blend was graded")
        void reportsNullWhenNothingIsGraded() {
            Blend blend = shipmentOf(
                    lot(null, null, null, null), "10000.000",
                    lot(null, null, null, null), "5000.000").blend();

            assertThat(blend.totalWeightKg()).isEqualByComparingTo("15000.000");
            assertThat(blend.moisturePercent()).isNull();
            assertThat(blend.moistureBasisKg()).isEqualByComparingTo("0");
        }

        /**
         * 214800 / 18000 does not terminate, so a divide() without an explicit
         * scale throws ArithmeticException. The first realistic example hits it,
         * which is why the scale is not left to chance.
         */
        @Test
        @DisplayName("rounds a non-terminating division instead of throwing")
        void survivesANonTerminatingDivision() {
            Blend blend = shipmentOf(
                    lot("11.50", null, null, null), "12000.000",
                    lot("12.80", null, null, null), "6000.000").blend();

            assertThat(blend.moisturePercent().scale()).isEqualTo(2);
        }
    }

    @Nested
    @DisplayName("classification composed by weight")
    class Classification {

        /**
         * 12,000 kg of screen 17/18 and 6,000 kg of 15/16 out of 18,000:
         *
         *   12000 / 18000 = 66.666... -> 66.67
         *    6000 / 18000 = 33.333... -> 33.33
         */
        @Test
        @DisplayName("reports each distinct value with its share of the total weight")
        void reportsSharesByWeight() {
            Blend blend = shipmentOf(
                    lot("11.50", "17/18", "T6", "HARD"), "12000.000",
                    lot("12.80", "15/16", "T4/5", "SOFT"), "6000.000").blend();

            assertThat(blend.screenSizes()).hasSize(2);
            assertThat(blend.screenSizes().get(0).value()).isEqualTo("17/18");
            assertThat(blend.screenSizes().get(0).weightKg()).isEqualByComparingTo("12000.000");
            assertThat(blend.screenSizes().get(0).sharePercent()).isEqualByComparingTo("66.67");
            assertThat(blend.screenSizes().get(1).value()).isEqualTo("15/16");
            assertThat(blend.screenSizes().get(1).sharePercent()).isEqualByComparingTo("33.33");
        }

        @Test
        @DisplayName("merges the weight of every lot sharing a value into one share")
        void mergesRepeatedValues() {
            Blend blend = shipmentOf(
                    lot("11.00", "17/18", "T6", "HARD"), "5000.000",
                    lot("11.00", "17/18", "T6", "SOFT"), "7000.000",
                    lot("11.00", "15/16", "T6", "HARD"), "8000.000").blend();

            // 17/18 appears in two lots: 5,000 + 7,000 = 12,000 of 20,000 = 60%,
            // which also puts it ahead of 15/16's 8,000 in the ordering.
            assertThat(blend.screenSizes()).hasSize(2);
            assertThat(blend.screenSizes().get(0).value()).isEqualTo("17/18");
            assertThat(blend.screenSizes().get(0).weightKg()).isEqualByComparingTo("12000.000");
            assertThat(blend.screenSizes().get(0).sharePercent()).isEqualByComparingTo("60.00");
            assertThat(blend.screenSizes().get(1).value()).isEqualTo("15/16");
            assertThat(blend.screenSizes().get(1).sharePercent()).isEqualByComparingTo("40.00");

            // T6 is the whole shipment, so it is a single 100% share.
            assertThat(blend.defectTypes()).hasSize(1);
            assertThat(blend.defectTypes().get(0).value()).isEqualTo("T6");
            assertThat(blend.defectTypes().get(0).sharePercent()).isEqualByComparingTo("100.00");
        }

        /**
         * The shares divide by the total weight, not by the graded weight, so
         * ungraded coffee shows up as a gap below 100% rather than being hidden
         * behind a denominator that quietly shrank.
         */
        @Test
        @DisplayName("leaves ungraded weight visible as a shortfall below 100%")
        void leavesUngradedWeightAsAGap() {
            Blend blend = shipmentOf(
                    lot("11.00", "17/18", null, null), "15000.000",
                    lot("11.00", null, null, null), "5000.000").blend();

            assertThat(blend.screenSizes()).hasSize(1);
            assertThat(blend.screenSizes().get(0).sharePercent()).isEqualByComparingTo("75.00");
            assertThat(blend.defectTypes()).isEmpty();
            assertThat(blend.cupQualities()).isEmpty();
        }

        @Test
        @DisplayName("orders shares by weight so the output is stable")
        void ordersSharesByWeight() {
            Blend blend = shipmentOf(
                    lot("11.00", "15/16", null, null), "1000.000",
                    lot("11.00", "17/18", null, null), "9000.000").blend();

            assertThat(blend.screenSizes())
                    .extracting(Blend.WeightedShare::value)
                    .containsExactly("17/18", "15/16");
        }
    }

    @Test
    @DisplayName("an empty shipment has a zero blend rather than no blend")
    void anEmptyShipmentBlendsToZero() {
        Blend blend = shipmentOf().blend();

        assertThat(blend.totalWeightKg()).isEqualByComparingTo("0");
        assertThat(blend.moisturePercent()).isNull();
        assertThat(blend.screenSizes()).isEmpty();
    }
}
