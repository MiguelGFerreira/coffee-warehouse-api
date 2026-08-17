package tech.migueldev.coffeewarehouse.api.exception;

/**
 * Thrown when a business code is already taken.
 * Mapped to 409 by {@link ApiExceptionHandler}.
 */
public class DuplicateCodeException extends RuntimeException {

    public DuplicateCodeException(String message) {
        super(message);
    }

    public static DuplicateCodeException of(String resource, String code) {
        return new DuplicateCodeException("%s with code %s already exists".formatted(resource, code));
    }
}
