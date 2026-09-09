package tech.migueldev.coffeewarehouse.repository;

import tech.migueldev.coffeewarehouse.domain.Shipment;
import tech.migueldev.coffeewarehouse.domain.ShipmentStatus;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface ShipmentRepository extends JpaRepository<Shipment, Long> {

    /**
     * The items come with the shipment, and so do the lot and the position of
     * each one.
     *
     * Every read of a shipment needs them: the blend averages the lots'
     * moisture, and the response carries the lot and position codes. With
     * open-in-view disabled a lazy collection touched in the controller would
     * blow up, and one query per item would be an N+1 on the aggregate root's
     * own contents.
     */
    @Override
    @EntityGraph(attributePaths = {"items", "items.lot", "items.sourcePosition"})
    Optional<Shipment> findById(Long id);

    boolean existsByCode(String code);

    /**
     * Listing deliberately does not fetch the items. A page of shipments each
     * dragging its full composition along would be a large join for a summary
     * nobody asked for, so the response for a listing carries no blend.
     */
    @Query("""
            SELECT s FROM Shipment s
            WHERE (:status IS NULL OR s.status = :status)
            """)
    Page<Shipment> search(ShipmentStatus status, Pageable pageable);
}
