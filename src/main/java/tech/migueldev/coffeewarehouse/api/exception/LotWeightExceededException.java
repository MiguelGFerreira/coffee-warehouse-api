package tech.migueldev.coffeewarehouse.api.exception;

/**
 * More weight was received for a lot than the lot has.
 *
 * {@code net_weight_kg} is what the producer delivered and it never changes, so
 * it is the ceiling on everything the ledger can ever record arriving. Mapped to
 * 409 by {@link ApiExceptionHandler}.
 */
public class LotWeightExceededException extends RuntimeException {

    public LotWeightExceededException(String message) {
        super(message);
    }
}
