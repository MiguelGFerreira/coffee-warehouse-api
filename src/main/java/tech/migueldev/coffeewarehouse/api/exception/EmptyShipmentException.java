package tech.migueldev.coffeewarehouse.api.exception;

/**
 * A shipment with no items cannot be confirmed: there is nothing to send, no
 * weight to divide by, and therefore no blend to freeze.
 * Mapped to 409 by {@link ApiExceptionHandler}.
 */
public class EmptyShipmentException extends RuntimeException {

    public EmptyShipmentException(String message) {
        super(message);
    }
}
