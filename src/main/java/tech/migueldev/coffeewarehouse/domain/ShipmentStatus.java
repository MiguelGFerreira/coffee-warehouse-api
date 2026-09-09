package tech.migueldev.coffeewarehouse.domain;

/**
 * Where a shipment is in its life.
 *
 * DRAFT is the only state in which the composition can change. Both other
 * states are terminal: CONFIRMED because its movements are already in the
 * ledger and the ledger is corrected by recording the opposite rather than by
 * undoing, CANCELLED because a shipment that never went out has nothing left
 * to say.
 */
public enum ShipmentStatus {

    DRAFT,
    CONFIRMED,
    CANCELLED;

    public boolean isTerminal() {
        return this != DRAFT;
    }
}
