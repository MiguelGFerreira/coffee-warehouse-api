package tech.migueldev.coffeewarehouse.api.exception;

/**
 * The weight arriving at a position would push it past its capacity.
 * Mapped to 409 by {@link ApiExceptionHandler}.
 */
public class CapacityExceededException extends RuntimeException {

    public CapacityExceededException(String message) {
        super(message);
    }
}
