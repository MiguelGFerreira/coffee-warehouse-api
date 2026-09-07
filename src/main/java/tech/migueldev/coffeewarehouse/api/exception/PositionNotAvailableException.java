package tech.migueldev.coffeewarehouse.api.exception;

/**
 * The position cannot receive stock because it is out of service.
 *
 * Stock can still leave a deactivated position -- that is how one is emptied.
 * Mapped to 409 by {@link ApiExceptionHandler}.
 */
public class PositionNotAvailableException extends RuntimeException {

    public PositionNotAvailableException(String message) {
        super(message);
    }
}
