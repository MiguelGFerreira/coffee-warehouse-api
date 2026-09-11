package tech.migueldev.coffeewarehouse.domain;

/**
 * What an account is allowed to do.
 *
 * Two roles rather than one, because a single role that every endpoint accepts
 * is authentication wearing authorization's clothes: it answers "is this
 * somebody?" and never "is this somebody who may do that?".
 *
 * <ul>
 *   <li>{@code OPERATOR} works the warehouse floor: records movements, composes
 *       and confirms shipments, reads everything.</li>
 *   <li>{@code ADMIN} additionally maintains the registry -- producers,
 *       warehouses, positions and lots. Creating a warehouse is not an
 *       operational act.</li>
 * </ul>
 *
 * Persisted as a string against a CHECK constraint, the same as every other enum
 * in this schema. The {@code ROLE_} prefix Spring Security expects is not stored:
 * it is a framework convention, and putting it in the database would leak the
 * framework into the schema.
 */
public enum UserRole {

    ADMIN,
    OPERATOR;

    /**
     * The authority name Spring Security matches on. {@code hasRole("ADMIN")}
     * looks for an authority literally called {@code ROLE_ADMIN}, so the prefix
     * is added here, in one place, rather than remembered at each call site.
     */
    public String authority() {
        return "ROLE_" + name();
    }
}
