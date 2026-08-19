package tech.migueldev.coffeewarehouse.repository;

import tech.migueldev.coffeewarehouse.domain.Lot;
import tech.migueldev.coffeewarehouse.domain.LotStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface LotRepository extends JpaRepository<Lot, Long> {

    @Override
    @EntityGraph(attributePaths = "producer")
    Optional<Lot> findById(Long id);

    @EntityGraph(attributePaths = "producer")
    Optional<Lot> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * One query for the three filters the roadmap asks for, each optional. The
     * alternative -- a Specification, or a method per combination -- costs more
     * machinery than three nullable parameters are worth at this size.
     */
    @EntityGraph(attributePaths = "producer")
    @Query("""
            SELECT l FROM Lot l
            WHERE (:status IS NULL OR l.status = :status)
              AND (:cropYear IS NULL OR l.cropYear = :cropYear)
              AND (:producerId IS NULL OR l.producer.id = :producerId)
            """)
    Page<Lot> search(LotStatus status, Integer cropYear, Long producerId, Pageable pageable);
}
