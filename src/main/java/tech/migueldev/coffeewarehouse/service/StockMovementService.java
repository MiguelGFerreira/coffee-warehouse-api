package tech.migueldev.coffeewarehouse.service;

import tech.migueldev.coffeewarehouse.api.dto.InboundRequest;
import tech.migueldev.coffeewarehouse.api.dto.OutboundRequest;
import tech.migueldev.coffeewarehouse.api.dto.TransferRequest;
import tech.migueldev.coffeewarehouse.api.exception.InsufficientBalanceException;
import tech.migueldev.coffeewarehouse.api.exception.InvalidMovementException;
import tech.migueldev.coffeewarehouse.api.exception.ResourceNotFoundException;
import tech.migueldev.coffeewarehouse.domain.Lot;
import tech.migueldev.coffeewarehouse.domain.StockMovement;
import tech.migueldev.coffeewarehouse.domain.StoragePosition;
import tech.migueldev.coffeewarehouse.repository.StockMovementRepository;
import tech.migueldev.coffeewarehouse.repository.StoragePositionRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * The movement ledger: the only place that writes to stock_movement, and the
 * only place that enforces the invariants spanning a lot and a position.
 *
 * Every operation follows the same shape -- guard the lot, lock the positions,
 * read the balances the guard needs, let the entities refuse, then append. The
 * append is the last thing that happens, because a ledger entry that had to be
 * taken back would defeat the point of an append-only table.
 */
@Service
public class StockMovementService {

    private final StockMovementRepository movements;
    private final StoragePositionRepository positions;
    private final LotService lotService;

    public StockMovementService(StockMovementRepository movements,
                                StoragePositionRepository positions,
                                LotService lotService) {
        this.movements = movements;
        this.positions = positions;
        this.lotService = lotService;
    }

    @Transactional
    public StockMovement recordInbound(InboundRequest request) {
        Lot lot = lotService.findById(request.lotId());
        lot.ensureMovable();

        StoragePosition target = lockPosition(request.targetPositionId());
        target.ensureCanReceive();
        target.ensureFits(movements.occupancyOf(target.getId()), request.weightKg());

        StockMovement movement = movements.save(StockMovement.inbound(
                lot, target, request.weightKg(), request.occurredAt(), request.reason()));

        lot.markStored();
        return movement;
    }

    @Transactional
    public StockMovement recordTransfer(TransferRequest request) {
        if (Objects.equals(request.sourcePositionId(), request.targetPositionId())) {
            throw new InvalidMovementException("Source and target positions must be different");
        }

        Lot lot = lotService.findById(request.lotId());
        lot.ensureMovable();

        List<StoragePosition> locked = lockPositionsInOrder(
                request.sourcePositionId(), request.targetPositionId());
        StoragePosition source = pick(locked, request.sourcePositionId());
        StoragePosition target = pick(locked, request.targetPositionId());

        ensureBalance(lot, source, request.weightKg());
        target.ensureCanReceive();
        target.ensureFits(movements.occupancyOf(target.getId()), request.weightKg());

        return movements.save(StockMovement.transfer(
                lot, source, target, request.weightKg(), request.occurredAt(), request.reason()));
    }

    /**
     * No capacity check: weight only leaves. The position is still locked, so
     * two concurrent outbounds cannot both pass the balance check and overdraw.
     *
     * Status is untouched. A lot that has left the warehouse is marked SHIPPED
     * by the shipment that took it, in Phase 4, and having two places write that
     * state would be one too many.
     */
    @Transactional
    public StockMovement recordOutbound(OutboundRequest request) {
        Lot lot = lotService.findById(request.lotId());
        lot.ensureMovable();

        StoragePosition source = lockPosition(request.sourcePositionId());
        ensureBalance(lot, source, request.weightKg());

        return movements.save(StockMovement.outbound(
                lot, source, request.weightKg(), request.occurredAt(), request.reason()));
    }

    @Transactional(readOnly = true)
    public BigDecimal occupancyOf(Long positionId) {
        return movements.occupancyOf(positionId);
    }

    @Transactional(readOnly = true)
    public BigDecimal balanceOfLot(Long lotId) {
        return movements.balanceOfLot(lotId);
    }

    @Transactional(readOnly = true)
    public List<StockMovement> statementOf(Long lotId) {
        return movements.findByLotIdOrderByOccurredAtAscIdAsc(lotId);
    }

    private void ensureBalance(Lot lot, StoragePosition source, BigDecimal weightKg) {
        BigDecimal available = movements.balanceOfLotAt(lot.getId(), source.getId());
        if (available.compareTo(weightKg) < 0) {
            throw new InsufficientBalanceException(
                    "Position %s holds %s kg of lot %s; %s kg were requested"
                            .formatted(source.getCode(), available, lot.getCode(), weightKg));
        }
    }

    /**
     * Loading with a forced version increment is what makes the capacity check
     * hold under concurrency: see the note on the repository method.
     */
    private StoragePosition lockPosition(Long id) {
        return positions.findByIdAndLock(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Storage position", id));
    }

    /**
     * Always lock by ascending id.
     *
     * A transfer touches two positions, so two transfers moving stock in
     * opposite directions between the same pair could each hold one version and
     * wait for the other. A fixed order makes that cycle impossible.
     */
    private List<StoragePosition> lockPositionsInOrder(Long first, Long second) {
        return java.util.stream.Stream.of(first, second)
                .sorted()
                .map(this::lockPosition)
                .toList();
    }

    private StoragePosition pick(List<StoragePosition> locked, Long id) {
        return locked.stream()
                .filter(position -> position.getId().equals(id))
                .findFirst()
                .orElseThrow(() -> ResourceNotFoundException.of("Storage position", id));
    }
}
