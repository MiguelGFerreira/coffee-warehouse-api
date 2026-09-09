package tech.migueldev.coffeewarehouse;

import static org.assertj.core.api.Assertions.assertThat;

import tech.migueldev.coffeewarehouse.api.dto.InboundRequest;
import tech.migueldev.coffeewarehouse.domain.Lot;
import tech.migueldev.coffeewarehouse.domain.Producer;
import tech.migueldev.coffeewarehouse.domain.StoragePosition;
import tech.migueldev.coffeewarehouse.domain.Warehouse;
import tech.migueldev.coffeewarehouse.repository.LotRepository;
import tech.migueldev.coffeewarehouse.repository.ProducerRepository;
import tech.migueldev.coffeewarehouse.repository.StockMovementRepository;
import tech.migueldev.coffeewarehouse.repository.StoragePositionRepository;
import tech.migueldev.coffeewarehouse.repository.WarehouseRepository;
import tech.migueldev.coffeewarehouse.service.StockMovementService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * The proof that the two ledger capacity invariants survive concurrency.
 *
 * An inbound has to satisfy one rule per aggregate: the position cannot end up
 * holding more than its capacity, and the lot cannot end up having more of it
 * received than the producer delivered. Both are aggregations over a table that
 * is only ever inserted into, so neither is protected by an ordinary
 * {@code @Version} -- nothing updates the row whose rule is at stake. Both
 * aggregates are therefore loaded with {@code OPTIMISTIC_FORCE_INCREMENT}.
 *
 * The race only exists between the moment a transaction reads its aggregation
 * and the moment it commits. Simply firing two threads at the service does not
 * reproduce it: the work is fast enough that the first commits before the
 * second reads, and such a test passes even with no protection at all -- which
 * makes it worse than no test. So the interleaving is forced. Each thread opens
 * its own transaction, calls the real service inside it, and then waits on a
 * barrier before committing. Both have read and appended while neither has
 * committed, which is exactly the window the invariants have to survive.
 * Nothing of the production logic is reimplemented here; only the commit
 * timing is.
 *
 * <p><b>Each test isolates exactly one aggregate.</b> That is the property that
 * makes them worth having, and it takes deliberate setup, because two
 * force-incremented aggregates in one operation can cover for each other:
 *
 * <ul>
 *   <li>the position race runs <b>two different lots into one position</b>, so
 *       the two lot versions cannot collide and only the position can be doing
 *       the serializing;</li>
 *   <li>the lot race runs <b>one lot into two different positions</b>, so the
 *       two position versions cannot collide and only the lot can be.</li>
 * </ul>
 *
 * The lot race additionally warms its lot up to STORED first. The first inbound
 * of a lot dirties its row through {@code markStored()}, and that alone would
 * bump the version -- hiding whether the forced increment is doing anything.
 * Warming it up strips that away and leaves the lock mode as the only thing
 * under test.
 *
 * This is also why the project uses Testcontainers and not H2: what is being
 * asserted is the behaviour of a real Postgres under two connections.
 */
class LedgerConcurrencyTest extends AbstractIntegrationTest {

    private static final BigDecimal CAPACITY = new BigDecimal("1000.000");
    private static final BigDecimal ROOMY = new BigDecimal("50000.000");

    @Autowired
    private StockMovementService service;

    @Autowired
    private StockMovementRepository movements;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ProducerRepository producerRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private StoragePositionRepository positionRepository;

    @Autowired
    private LotRepository lotRepository;

    /** Net weight far above anything these tests move: the ceiling stays out of the way. */
    private Lot lot;
    private Lot otherLot;

    /** Net weight of 1,000 kg: the ceiling is what the lot race is aiming at. */
    private Lot cappedLot;

    private StoragePosition position;
    private StoragePosition roomyA;
    private StoragePosition roomyB;

    @BeforeEach
    void seed() {
        Producer producer = producerRepository.save(
                new Producer("COP-001", "Cooperativa Serra Alta", "Guaxupe", "MG"));
        Warehouse warehouse = warehouseRepository.save(
                new Warehouse("WH1", "Armazem Central", "Guaxupe", "MG"));

        position = positionRepository.save(
                new StoragePosition(warehouse, "01", "01", "01", CAPACITY));
        roomyA = positionRepository.save(
                new StoragePosition(warehouse, "02", "01", "01", ROOMY));
        roomyB = positionRepository.save(
                new StoragePosition(warehouse, "03", "01", "01", ROOMY));

        lot = lotRepository.save(new Lot("LOT-001", producer, 2025,
                new BigDecimal("100000.000"), LocalDate.of(2025, 6, 10)));
        otherLot = lotRepository.save(new Lot("LOT-002", producer, 2025,
                new BigDecimal("100000.000"), LocalDate.of(2025, 6, 11)));
        cappedLot = lotRepository.save(new Lot("LOT-003", producer, 2025,
                new BigDecimal("1000.000"), LocalDate.of(2025, 6, 12)));
    }

    @Test
    @DisplayName("two lots that both find room in one position before either commits: only one gets in")
    void overlappingInboundsCannotOverflowThePosition() throws Exception {
        // 2 x 600 against a capacity of 1000: if both committed, the position
        // would hold 1200 kg of the 1000 it can take. Two different lots, so
        // nothing but the position itself can refuse the second one.
        List<Throwable> failures = runOverlapping(
                new InboundRequest(lot.getId(), position.getId(), new BigDecimal("600.000"), null, null),
                new InboundRequest(otherLot.getId(), position.getId(), new BigDecimal("600.000"), null, null));

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(movements.occupancyOf(position.getId())).isEqualByComparingTo("600.000");
    }

    @Test
    @DisplayName("one lot into two positions cannot be received beyond its net weight")
    void overlappingInboundsCannotExceedTheLotNetWeight() throws Exception {
        // Take the lot out of AWAITING_ALLOCATION first, so the race below
        // cannot be won by markStored() dirtying the row instead of by the
        // forced version increment.
        service.recordInbound(new InboundRequest(
                cappedLot.getId(), roomyA.getId(), new BigDecimal("100.000"), null, "warm-up"));

        // 100 already received, then 2 x 600 against a net weight of 1000: each
        // transaction reads 100 and finds room, but together they would put
        // 1300 kg of a 1000 kg lot into the warehouse. The two positions are
        // different and both have room, so only the lot can refuse.
        List<Throwable> failures = runOverlapping(
                new InboundRequest(cappedLot.getId(), roomyA.getId(), new BigDecimal("600.000"), null, null),
                new InboundRequest(cappedLot.getId(), roomyB.getId(), new BigDecimal("600.000"), null, null));

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(movements.totalInboundOf(cappedLot.getId())).isEqualByComparingTo("700.000");
        assertThat(movements.totalInboundOf(cappedLot.getId()))
                .isLessThanOrEqualTo(cappedLot.getNetWeightKg());
    }

    @Test
    @DisplayName("the loser of the race leaves nothing behind in the ledger")
    void theRejectedTransactionAppendsNothing() throws Exception {
        runOverlapping(
                new InboundRequest(lot.getId(), position.getId(), new BigDecimal("600.000"), null, null),
                new InboundRequest(otherLot.getId(), position.getId(), new BigDecimal("600.000"), null, null));

        // The losing transaction had already inserted its movement before the
        // version bump was refused. Its rollback has to take that row with it,
        // or the ledger would record weight the position never received.
        // count() rather than findAll(): when this assertion fails, AssertJ
        // renders the movements it got, and rendering one touches its lazy lot
        // outside the session -- turning a clear "expected 1 but was 2" into a
        // LazyInitializationException that explains nothing.
        assertThat(movements.count()).isEqualTo(1);
        assertThat(movements.occupancyOf(position.getId())).isEqualByComparingTo("600.000");
    }

    @Test
    @DisplayName("without contention every inbound that fits is accepted")
    void sequentialInboundsAllFitWhenThereIsRoom() {
        for (int i = 0; i < 5; i++) {
            service.recordInbound(new InboundRequest(
                    lot.getId(), position.getId(), new BigDecimal("200.000"), null, null));
        }

        assertThat(movements.occupancyOf(position.getId())).isEqualByComparingTo(CAPACITY);
    }

    /**
     * Runs two inbounds in two transactions held open until both have read and
     * appended, then lets them commit. Returns the failures.
     */
    private List<Throwable> runOverlapping(InboundRequest first, InboundRequest second) throws Exception {
        CyclicBarrier bothHaveRead = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Throwable> failures = new ArrayList<>();

        try {
            List<Future<Throwable>> results = List.of(
                    pool.submit(attempt(first, bothHaveRead)),
                    pool.submit(attempt(second, bothHaveRead)));

            for (Future<Throwable> result : results) {
                Throwable failure = result.get(30, TimeUnit.SECONDS);
                if (failure != null) {
                    failures.add(failure);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return failures;
    }

    private Callable<Throwable> attempt(InboundRequest request, CyclicBarrier bothHaveRead) {
        return () -> {
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            try {
                transaction.execute(status -> {
                    service.recordInbound(request);
                    waitForPeer(bothHaveRead);
                    return null;
                });
                return null;
            } catch (RuntimeException failed) {
                // Release the peer if it is still waiting, so a failure here
                // never turns into a hung test.
                bothHaveRead.reset();
                return failed;
            }
        };
    }

    private void waitForPeer(CyclicBarrier barrier) {
        try {
            barrier.await(20, TimeUnit.SECONDS);
        } catch (Exception interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("peer transaction never arrived", interrupted);
        }
    }
}
