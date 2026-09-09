package tech.migueldev.coffeewarehouse.repository;

import tech.migueldev.coffeewarehouse.domain.StoragePosition;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface StoragePositionRepository extends JpaRepository<StoragePosition, Long> {

    /**
     * The warehouse is fetched with the position everywhere it is read: the
     * response carries the warehouse code, and with open-in-view disabled a lazy
     * proxy touched in the controller would blow up. On a listing it would also
     * be one query per row.
     */
    @Override
    @EntityGraph(attributePaths = "warehouse")
    Optional<StoragePosition> findById(Long id);

    @EntityGraph(attributePaths = "warehouse")
    Optional<StoragePosition> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * Loads a position and forces its version to be bumped when the transaction
     * commits, even though the row itself does not change.
     *
     * This is what makes the capacity invariant safe. Recording a movement only
     * INSERTs into the ledger -- it never updates the position -- so a plain
     * @Version would never see a conflict: two concurrent transactions would
     * both read the same occupancy, both find room, and both insert. Forcing
     * the increment turns the position into the serialization point of its own
     * invariant, which is exactly the job of an aggregate root.
     *
     * Deliberately no {@code @EntityGraph} here, unlike every other finder in
     * this interface. Hibernate cascades the lock mode to whatever the query
     * join-fetches, so fetching the warehouse would force *its* version up too
     * and quietly promote the whole warehouse to the serialization point --
     * two movements into two unrelated positions of the same warehouse would
     * collide and get a 409 neither of them earned. Nothing on the movement
     * path reads the warehouse, so the association stays lazy.
     */
    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    @Query("SELECT p FROM StoragePosition p WHERE p.id = :id")
    Optional<StoragePosition> findByIdAndLock(@Param("id") Long id);

    @EntityGraph(attributePaths = "warehouse")
    @Query("""
            SELECT p FROM StoragePosition p
            WHERE (:warehouseId IS NULL OR p.warehouse.id = :warehouseId)
              AND (:active IS NULL OR p.active = :active)
            """)
    Page<StoragePosition> search(Long warehouseId, Boolean active, Pageable pageable);
}
