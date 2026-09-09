package tech.migueldev.coffeewarehouse.repository;

import tech.migueldev.coffeewarehouse.domain.Lot;
import tech.migueldev.coffeewarehouse.domain.LotStatus;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface LotRepository extends JpaRepository<Lot, Long> {

    @Override
    @EntityGraph(attributePaths = "producer")
    Optional<Lot> findById(Long id);

    @EntityGraph(attributePaths = "producer")
    Optional<Lot> findByCode(String code);

    boolean existsByCode(String code);

    /**
     * Loads a lot and forces its version to be bumped at commit, for the same
     * reason positions get this treatment: the net weight ceiling is an
     * aggregation over rows that only ever get inserted.
     *
     * The lot's own {@code @Version} is not enough on its own. Only the *first*
     * inbound dirties the lot row, through {@code markStored()}; every one after
     * that leaves it untouched, so two concurrent inbounds into two different
     * positions would both read the same total received, both find room under
     * the net weight, and both insert. Forcing the increment makes the lot the
     * serialization point of its own invariant, exactly as D2 does for the
     * position.
     *
     * Used by an inbound, and by anything that claims a lot for a shipment --
     * availability is summed the same way, from rows that are only ever
     * inserted, and markReserved() dirties the row only on the first claim.
     * A transfer or an outbound does not need it: neither changes what has been
     * received or what is spoken for, so paying for it there would buy nothing
     * and would turn two unrelated transfers of the same lot into a spurious
     * 409.
     *
     * Deliberately no {@code @EntityGraph} here, unlike every other finder in
     * this interface. Hibernate cascades the lock mode to whatever the query
     * join-fetches, so fetching the producer would force *its* version up too
     * and make the producer the serialization point -- two inbounds of two
     * unrelated lots from the same producer would collide. Nothing on the
     * inbound path reads the producer, so the association stays lazy.
     */
    @Lock(LockModeType.OPTIMISTIC_FORCE_INCREMENT)
    @Query("SELECT l FROM Lot l WHERE l.id = :id")
    Optional<Lot> findByIdAndLock(@Param("id") Long id);

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
