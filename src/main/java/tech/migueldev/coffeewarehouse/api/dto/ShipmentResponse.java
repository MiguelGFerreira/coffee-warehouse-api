package tech.migueldev.coffeewarehouse.api.dto;

import tech.migueldev.coffeewarehouse.domain.Shipment;
import tech.migueldev.coffeewarehouse.domain.ShipmentStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * A shipment with its composition and its blend.
 *
 * Two shapes, because a listing does not fetch the items: {@link #summary} omits
 * both, and {@link #from} carries them. Serving the full shape from a page would
 * mean dragging every composition along for a summary nobody asked for.
 */
public record ShipmentResponse(
        Long id,
        String code,
        ShipmentStatus status,
        String destination,
        LocalDate scheduledFor,
        OffsetDateTime confirmedAt,
        List<ShipmentItemResponse> items,
        BlendResponse blend
) {

    public static ShipmentResponse from(Shipment shipment) {
        return new ShipmentResponse(
                shipment.getId(),
                shipment.getCode(),
                shipment.getStatus(),
                shipment.getDestination(),
                shipment.getScheduledFor(),
                shipment.getConfirmedAt(),
                shipment.getItems().stream().map(ShipmentItemResponse::from).toList(),
                BlendResponse.from(shipment.blend()));
    }

    public static ShipmentResponse summary(Shipment shipment) {
        return new ShipmentResponse(
                shipment.getId(),
                shipment.getCode(),
                shipment.getStatus(),
                shipment.getDestination(),
                shipment.getScheduledFor(),
                shipment.getConfirmedAt(),
                null,
                null);
    }
}
