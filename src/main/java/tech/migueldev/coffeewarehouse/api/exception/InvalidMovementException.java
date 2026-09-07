package tech.migueldev.coffeewarehouse.api.exception;

/**
 * The movement is malformed in a way Bean Validation cannot express on a single
 * field, such as a transfer whose source and target are the same position.
 * Mapped to 400 by {@link ApiExceptionHandler}.
 */
public class InvalidMovementException extends RuntimeException {

    public InvalidMovementException(String message) {
        super(message);
    }
}
