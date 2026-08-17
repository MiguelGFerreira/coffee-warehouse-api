package tech.migueldev.coffeewarehouse.api.exception;

/**
 * Thrown when a resource addressed by the request does not exist.
 * Mapped to 404 by {@link ApiExceptionHandler}.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public static ResourceNotFoundException of(String resource, Object identifier) {
        return new ResourceNotFoundException("%s %s not found".formatted(resource, identifier));
    }
}
