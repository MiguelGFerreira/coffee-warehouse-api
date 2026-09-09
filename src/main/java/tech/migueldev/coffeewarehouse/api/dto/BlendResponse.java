package tech.migueldev.coffeewarehouse.api.dto;

import tech.migueldev.coffeewarehouse.domain.Blend;

import java.math.BigDecimal;
import java.util.List;

/**
 * The blend of a shipment.
 *
 * {@code moistureBasisKg} is not decoration: lots with no moisture reading are
 * left out of the average, so without it a caller cannot tell whether 11.93%
 * describes the whole shipment or a third of it.
 */
public record BlendResponse(
        BigDecimal totalWeightKg,
        BigDecimal moisturePercent,
        BigDecimal moistureBasisKg,
        List<WeightedShareResponse> screenSizes,
        List<WeightedShareResponse> defectTypes,
        List<WeightedShareResponse> cupQualities
) {

    /**
     * The share is of the shipment's total weight, so the shares of one
     * attribute sum to 100% only when every lot in the blend carries a value
     * for it. A shortfall is ungraded coffee, not a rounding error.
     */
    public record WeightedShareResponse(String value, BigDecimal weightKg, BigDecimal sharePercent) {

        static WeightedShareResponse from(Blend.WeightedShare share) {
            return new WeightedShareResponse(share.value(), share.weightKg(), share.sharePercent());
        }
    }

    public static BlendResponse from(Blend blend) {
        return new BlendResponse(
                blend.totalWeightKg(),
                blend.moisturePercent(),
                blend.moistureBasisKg(),
                blend.screenSizes().stream().map(WeightedShareResponse::from).toList(),
                blend.defectTypes().stream().map(WeightedShareResponse::from).toList(),
                blend.cupQualities().stream().map(WeightedShareResponse::from).toList());
    }
}
