package tech.migueldev.coffeewarehouse.api.exception;

/**
 * The source position does not hold enough of the lot to satisfy the movement.
 * Mapped to 409 by {@link ApiExceptionHandler}.
 */
public class InsufficientBalanceException extends RuntimeException {

    public InsufficientBalanceException(String message) {
        super(message);
    }
}
