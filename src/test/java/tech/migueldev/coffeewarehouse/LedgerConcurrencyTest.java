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
 * The proof that the capacity invariant survives concurrency.
 *
 * The race only exists between the moment a transaction reads the occupancy and
 * the moment it commits. Simply firing two threads at the service does not
 * reproduce it: the work is fast enough that the first commits before the
 * second reads, and such a test passes even with no protection at all -- which
 * makes it worse than no test.
 *
 * So the interleaving is forced. Each thread opens its own transaction, calls
 * the real service inside it, and then waits on a barrier before committing.
 * Both have read the occupancy and appended to the ledger while neither has
 * committed, which is exactly the window the invariant has to survive. Nothing
 * of the production logic is reimplemented here; only the commit timing is.
 *
 * The lot is deliberately already STORED before the race starts. The first
 * inbound of a lot dirties its row through markStored(), and the version on the
 * lot would then serialize the two transactions all by itself -- hiding whether
 * the position defends its own capacity at all. Warming the lot up first strips
 * that away and leaves the position as the only thing under test.
 *
 * This is also why the project uses Testcontainers and not H2: what is being
 * asserted is the behaviour of a real Postgres under two connections.
 */
class LedgerConcurrencyTest extends AbstractIntegrationTest {

    private static final BigDecimal CAPACITY = new BigDecimal("1000.000");

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

    private Lot lot;
    private StoragePosition position;
    private StoragePosition elsewhere;

    @BeforeEach
    void seed() {
        Producer producer = producerRepository.save(
                new Producer("COP-001", "Cooperativa Serra Alta", "Guaxupe", "MG"));
        Warehouse warehouse = warehouseRepository.save(
                new Warehouse("WH1", "Armazem Central", "Guaxupe", "MG"));
        position = positionRepository.save(
                new StoragePosition(warehouse, "01", "01", "01", CAPACITY));
        elsewhere = positionRepository.save(
                new StoragePosition(warehouse, "02", "01", "01", new BigDecimal("50000.000")));
        lot = lotRepository.save(new Lot("LOT-001", producer, 2025,
                new BigDecimal("100000.000"), LocalDate.of(2025, 6, 10)));

        // Take the lot out of AWAITING_ALLOCATION somewhere else, so the race
        // below cannot be won by the version on the lot instead of the position.
        service.recordInbound(new InboundRequest(
                lot.getId(), elsewhere.getId(), new BigDecimal("100.000"), null, "warm-up"));
    }

    @Test
    @DisplayName("two transactions that both find room before either commits: only one gets in")
    void overlappingInboundsCannotOverflowThePosition() throws Exception {
        // 2 x 600 against a capacity of 1000: if both committed, the position
        // would hold 1200 kg of the 1000 it can take.
        List<Throwable> failures = runOverlappingInbounds("600.000");

        assertThat(failures).hasSize(1);
        assertThat(failures.get(0)).isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(movements.occupancyOf(position.getId())).isEqualByComparingTo("600.000");
    }

    @Test
    @DisplayName("the loser of the race leaves nothing behind in the ledger")
    void theRejectedTransactionAppendsNothing() throws Exception {
        runOverlappingInbounds("600.000");

        // The losing transaction had already inserted its movement before the
        // version bump was refused. Its rollback has to take that row with it,
        // or the ledger would record weight the position never received.
        assertThat(movements.findAll()).hasSize(2); // the warm-up plus the winner
        assertThat(movements.balanceOfLot(lot.getId())).isEqualByComparingTo("700.000");
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
     * Runs two inbounds of the same weight in two transactions held open until
     * both have read and appended, then lets them commit. Returns the failures.
     */
    private List<Throwable> runOverlappingInbounds(String weight) throws Exception {
        CyclicBarrier bothHaveRead = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Throwable> failures = new ArrayList<>();

        try {
            Callable<Throwable> attempt = () -> {
                TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                try {
                    transaction.execute(status -> {
                        service.recordInbound(new InboundRequest(
                                lot.getId(), position.getId(), new BigDecimal(weight), null, null));
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

            List<Future<Throwable>> results = List.of(pool.submit(attempt), pool.submit(attempt));
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

    private void waitForPeer(CyclicBarrier barrier) {
        try {
            barrier.await(20, TimeUnit.SECONDS);
        } catch (Exception interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("peer transaction never arrived", interrupted);
        }
    }
}
