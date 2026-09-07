package tech.migueldev.coffeewarehouse.domain;

/**
 * What a movement does to the warehouse.
 *
 * INBOUND puts weight into a position, OUTBOUND takes it out, TRANSFER
 * moves it between two. The endpoints each type is allowed to have are
 * enforced by a CHECK in the schema, not only by the code that writes it.
 */
public enum MovementType {

    INBOUND,
    TRANSFER,
    OUTBOUND
}
