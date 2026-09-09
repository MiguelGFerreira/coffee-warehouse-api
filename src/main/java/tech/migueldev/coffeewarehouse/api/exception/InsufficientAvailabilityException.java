package tech.migueldev.coffeewarehouse.api.exception;

/**
 * The stock is there, but it is already committed to another draft shipment.
 *
 * Deliberately distinct from {@link InsufficientBalanceException}: the two look
 * alike and call for opposite remedies. No balance means the coffee is not in
 * that position at all; no availability means it is, and someone else has
 * claimed it. A client that cannot tell them apart cannot tell a user whether
 * to pick elsewhere or to go free up a draft.
 *
 * Mapped to 409 by {@link ApiExceptionHandler}.
 */
public class InsufficientAvailabilityException extends RuntimeException {

    public InsufficientAvailabilityException(String message) {
        super(message);
    }
}
