package tech.migueldev.coffeewarehouse.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * What to pick, and from where, to make up a requested weight.
 *
 * Advice and nothing more: it reserves nothing, moves nothing and writes
 * nothing. Turning a line into a claim is a POST to the shipment's items, which
 * re-checks availability at that moment -- between the suggestion and the claim,
 * someone else may have taken the same coffee.
 *
 * {@code shortfallKg} is reported rather than treated as an error. "There is
 * only 14 tonnes of the oldest crop, here is where it is" is a useful answer to
 * a request for 18; a 409 would not be.
 */
public record PickingSuggestionResponse(
        BigDecimal requestedKg,
        BigDecimal suggestedKg,
        BigDecimal shortfallKg,
        boolean fullyCovered,
        List<PickingLine> lines
) {

    /**
     * @param weightKg    how much to take from this pair
     * @param availableKg how much is there in total, so a caller can see the
     *                    headroom a line is leaving behind
     */
    public record PickingLine(
            Long lotId,
            String lotCode,
            Integer cropYear,
            BigDecimal moisturePercent,
            Long sourcePositionId,
            String sourcePositionCode,
            BigDecimal weightKg,
            BigDecimal availableKg
    ) {
    }
}
