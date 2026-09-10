package tech.migueldev.coffeewarehouse.api.exception;

/**
 * The movement claims to have happened before something already recorded for
 * the same lot at the same position.
 *
 * The ledger is append-only in time as well as in row order, which is what lets
 * every balance check use the current sum: "how much is there now" is only the
 * right question if now is also when the movement happened.
 *
 * Mapped to 409 by {@link ApiExceptionHandler} rather than 400: the request is
 * well formed, and whether it is acceptable depends on history it collides with.
 */
public class OutOfOrderMovementException extends RuntimeException {

    public OutOfOrderMovementException(String message) {
        super(message);
    }
}
