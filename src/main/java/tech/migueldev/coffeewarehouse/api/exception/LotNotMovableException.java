package tech.migueldev.coffeewarehouse.api.exception;

/**
 * The lot is in a state that accepts no further movement -- today that means
 * SHIPPED, which is terminal. Mapped to 409 by {@link ApiExceptionHandler}.
 */
public class LotNotMovableException extends RuntimeException {

    public LotNotMovableException(String message) {
        super(message);
    }
}
