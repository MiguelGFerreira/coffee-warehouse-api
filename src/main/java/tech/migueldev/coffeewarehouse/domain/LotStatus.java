package tech.migueldev.coffeewarehouse.domain;

/**
 * Lifecycle of a lot: AWAITING_ALLOCATION -> STORED -> RESERVED -> SHIPPED.
 *
 * Persisted as a string so the column stays readable and matches the CHECK
 * constraint in the schema. Ordinal persistence would tie the database to the
 * declaration order of this enum, which is exactly the kind of hidden coupling
 * a reordering breaks silently.
 *
 * The transitions themselves are not modeled here: they are driven by the
 * movement ledger and belong to Phase 3. A lot created through the registry is
 * always AWAITING_ALLOCATION.
 */
public enum LotStatus {

    AWAITING_ALLOCATION,
    STORED,
    RESERVED,
    SHIPPED
}
