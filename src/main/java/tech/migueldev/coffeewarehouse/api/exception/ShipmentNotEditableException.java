package tech.migueldev.coffeewarehouse.api.exception;

/**
 * The shipment is no longer DRAFT, so its composition is closed.
 * Mapped to 409 by {@link ApiExceptionHandler}.
 */
public class ShipmentNotEditableException extends RuntimeException {

    public ShipmentNotEditableException(String message) {
        super(message);
    }
}
