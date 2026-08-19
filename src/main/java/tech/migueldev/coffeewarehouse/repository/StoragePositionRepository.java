package tech.migueldev.coffeewarehouse.repository;

import tech.migueldev.coffeewarehouse.domain.StoragePosition;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

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

    @EntityGraph(attributePaths = "warehouse")
    @Query("""
            SELECT p FROM StoragePosition p
            WHERE (:warehouseId IS NULL OR p.warehouse.id = :warehouseId)
              AND (:active IS NULL OR p.active = :active)
            """)
    Page<StoragePosition> search(Long warehouseId, Boolean active, Pageable pageable);
}
