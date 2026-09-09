package tech.migueldev.coffeewarehouse.service;

import tech.migueldev.coffeewarehouse.api.dto.ShipmentItemRequest;
import tech.migueldev.coffeewarehouse.api.dto.ShipmentItemWeightRequest;
import tech.migueldev.coffeewarehouse.api.dto.ShipmentRequest;
import tech.migueldev.coffeewarehouse.api.exception.DuplicateCodeException;
import tech.migueldev.coffeewarehouse.api.exception.InsufficientAvailabilityException;
import tech.migueldev.coffeewarehouse.api.exception.ResourceNotFoundException;
import tech.migueldev.coffeewarehouse.domain.Lot;
import tech.migueldev.coffeewarehouse.domain.Shipment;
import tech.migueldev.coffeewarehouse.domain.ShipmentItem;
import tech.migueldev.coffeewarehouse.domain.ShipmentStatus;
import tech.migueldev.coffeewarehouse.domain.StoragePosition;
import tech.migueldev.coffeewarehouse.repository.ShipmentItemRepository;
import tech.migueldev.coffeewarehouse.repository.ShipmentRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * Composition of a shipment, and the reservation that composing one implies.
 *
 * <h2>What "available" means here</h2>
 *
 * Putting a lot on a draft shipment moves nothing, so the ledger balance is
 * untouched -- which is exactly why the balance alone is the wrong number to
 * check a new line against. Two shipments would each see the full 10,000 kg and
 * each claim it. The number that matters is:
 *
 * <pre>
 *   available = balance at the position - weight committed to other draft shipments
 * </pre>
 *
 * Both halves are aggregations: one over the movement ledger, one over the
 * shipment items. Neither is a stored counter, for the same reason there is no
 * {@code current_occupancy} column -- a claim and the stock behind it cannot
 * drift apart if the claim is never written down twice.
 */
@Service
public class ShipmentService {

    private final ShipmentRepository shipments;
    private final ShipmentItemRepository items;
    private final LotService lotService;
    private final StoragePositionService positionService;
    private final StockMovementService movementService;

    public ShipmentService(ShipmentRepository shipments,
                           ShipmentItemRepository items,
                           LotService lotService,
                           StoragePositionService positionService,
                           StockMovementService movementService) {
        this.shipments = shipments;
        this.items = items;
        this.lotService = lotService;
        this.positionService = positionService;
        this.movementService = movementService;
    }

    @Transactional
    public Shipment create(ShipmentRequest request) {
        Shipment shipment = new Shipment(request.code(), request.destination(),
                request.scheduledFor());

        if (shipments.existsByCode(shipment.getCode())) {
            throw DuplicateCodeException.of("Shipment", shipment.getCode());
        }
        return shipments.save(shipment);
    }

    /**
     * Adds a line, after checking that the stock behind it is actually free.
     *
     * The lot has to be movable for the same reason a movement does: a SHIPPED
     * lot has left and cannot be promised to anyone.
     */
    @Transactional
    public ShipmentItem addItem(Long shipmentId, ShipmentItemRequest request) {
        Shipment shipment = findById(shipmentId);
        shipment.ensureEditable();

        // Locked for the D4 reason, one aggregation over: availability is summed
        // from rows that are only ever inserted, and markReserved() dirties the
        // lot only on the first claim -- so without a forced increment two
        // concurrent adds would both read the same free weight and both take it.
        Lot lot = lotService.findByIdAndLock(request.lotId());
        lot.ensureMovable();
        StoragePosition position = positionService.findById(request.sourcePositionId());

        // The line's resulting total, not the increment: addItem merges into an
        // existing line for the same (lot, position), so what has to fit is the
        // sum, and the shipment's own current line is excluded from the reserved
        // figure below rather than being counted against itself.
        BigDecimal resulting = shipment.findItem(lot, position)
                .map(existing -> existing.getWeightKg().add(request.weightKg()))
                .orElse(request.weightKg());

        ensureAvailable(shipment, lot, position, resulting);

        ShipmentItem item = shipment.addItem(lot, position, request.weightKg());
        lot.markReserved();
        return item;
    }

    @Transactional
    public Shipment changeItemWeight(Long shipmentId, Long itemId,
                                     ShipmentItemWeightRequest request) {
        Shipment shipment = findById(shipmentId);
        shipment.ensureEditable();

        ShipmentItem item = itemOf(shipment, itemId);
        // Same lock, same reason as addItem: raising a line's weight claims more
        // of the lot, so it races with anyone else claiming the same lot.
        lotService.findByIdAndLock(item.getLot().getId());
        ensureAvailable(shipment, item.getLot(), item.getSourcePosition(), request.weightKg());

        shipment.changeItemWeight(itemId, request.weightKg());
        return shipment;
    }

    /**
     * Removes a line and lets the lot out of RESERVED, but only if no other
     * draft shipment still holds it.
     *
     * That check is the whole reason the status is recomputed rather than
     * toggled: a lot on two drafts, released by one, is still spoken for by the
     * other, and a naive flip would advertise it as free.
     */
    @Transactional
    public Shipment removeItem(Long shipmentId, Long itemId) {
        Shipment shipment = findById(shipmentId);
        shipment.ensureEditable();

        Lot lot = itemOf(shipment, itemId).getLot();
        shipment.removeItem(itemId);

        if (!stillReservedElsewhere(lot, shipment)) {
            lot.releaseReservation();
        }
        return shipment;
    }

    @Transactional
    public Shipment cancel(Long shipmentId) {
        Shipment shipment = findById(shipmentId);
        shipment.ensureEditable();

        // Release before cancelling: once the status is no longer DRAFT the
        // items stop counting as reservations, so the lots have to be let go
        // here or they would stay RESERVED with nothing holding them.
        shipment.getItems().stream()
                .map(ShipmentItem::getLot)
                .distinct()
                .filter(lot -> !stillReservedElsewhere(lot, shipment))
                .forEach(Lot::releaseReservation);

        shipment.cancel();
        return shipment;
    }

    @Transactional(readOnly = true)
    public Shipment findById(Long id) {
        return shipments.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Shipment", id));
    }

    @Transactional(readOnly = true)
    public Page<Shipment> search(ShipmentStatus status, Pageable pageable) {
        return shipments.search(status, pageable);
    }

    /**
     * How much of a lot can still be promised out of a position: what the ledger
     * says is there, minus what other draft shipments have already claimed.
     */
    @Transactional(readOnly = true)
    public BigDecimal availableOfLotAt(Long lotId, Long positionId, Long excludingShipmentId) {
        return movementService.balanceOfLotAt(lotId, positionId)
                .subtract(items.reservedOfLotAt(lotId, positionId, excludingShipmentId));
    }

    private void ensureAvailable(Shipment shipment, Lot lot, StoragePosition position,
                                 BigDecimal wanted) {
        BigDecimal available = availableOfLotAt(lot.getId(), position.getId(), shipment.getId());
        if (available.compareTo(wanted) < 0) {
            throw new InsufficientAvailabilityException(
                    ("Position %s has %s kg of lot %s free after other draft shipments; "
                            + "%s kg were requested")
                            .formatted(position.getCode(), available, lot.getCode(), wanted));
        }
    }

    private boolean stillReservedElsewhere(Lot lot, Shipment shipment) {
        return items.existsDraftReservationForLot(lot.getId(), shipment.getId());
    }

    private ShipmentItem itemOf(Shipment shipment, Long itemId) {
        return shipment.getItems().stream()
                .filter(item -> item.getId().equals(itemId))
                .findFirst()
                .orElseThrow(() -> ResourceNotFoundException.of("Shipment item", itemId));
    }
}
