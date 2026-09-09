package tech.migueldev.coffeewarehouse.api.exception;

/**
 * A position would end up holding more than its capacity.
 *
 * Raised from both directions of the same invariant: weight arriving that does
 * not fit, and a capacity re-rated below what the ledger already holds. One
 * invariant, one problem type. Mapped to 409 by {@link ApiExceptionHandler}.
 */
public class CapacityExceededException extends RuntimeException {

    public CapacityExceededException(String message) {
        super(message);
    }
}
