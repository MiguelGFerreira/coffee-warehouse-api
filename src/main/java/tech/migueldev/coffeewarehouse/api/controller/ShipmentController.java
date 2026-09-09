package tech.migueldev.coffeewarehouse.api.controller;

import tech.migueldev.coffeewarehouse.api.dto.BlendResponse;
import tech.migueldev.coffeewarehouse.api.dto.ShipmentItemRequest;
import tech.migueldev.coffeewarehouse.api.dto.ShipmentItemResponse;
import tech.migueldev.coffeewarehouse.api.dto.ShipmentItemWeightRequest;
import tech.migueldev.coffeewarehouse.api.dto.ShipmentRequest;
import tech.migueldev.coffeewarehouse.api.dto.ShipmentResponse;
import tech.migueldev.coffeewarehouse.domain.Shipment;
import tech.migueldev.coffeewarehouse.domain.ShipmentItem;
import tech.migueldev.coffeewarehouse.domain.ShipmentStatus;
import tech.migueldev.coffeewarehouse.service.ShipmentService;

import jakarta.validation.Valid;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * A shipment is built up, then confirmed or cancelled.
 *
 * Items live under the shipment because they have no identity without it: there
 * is no {@code /api/shipment-items}, and there should not be. Confirm and cancel
 * are PATCH transitions rather than a PUT of the status field, so the API says
 * what happens rather than letting a client assign any state it likes -- there
 * is no path back from either.
 */
@RestController
@RequestMapping("/api/shipments")
public class ShipmentController {

    private final ShipmentService service;

    public ShipmentController(ShipmentService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ShipmentResponse> create(@RequestBody @Valid ShipmentRequest request,
                                                   UriComponentsBuilder uriBuilder) {
        Shipment shipment = service.create(request);
        URI location = uriBuilder.path("/api/shipments/{id}")
                .buildAndExpand(shipment.getId()).toUri();
        return ResponseEntity.created(location).body(ShipmentResponse.from(shipment));
    }

    @GetMapping("/{id}")
    public ShipmentResponse findById(@PathVariable Long id) {
        return ShipmentResponse.from(service.findById(id));
    }

    /**
     * Summaries only: a page of shipments does not fetch their compositions, so
     * the listing carries no items and no blend.
     */
    @GetMapping
    public PagedModel<ShipmentResponse> search(
            @RequestParam(required = false) ShipmentStatus status,
            @PageableDefault(size = 20, sort = "code", direction = Sort.Direction.ASC) Pageable pageable) {

        return new PagedModel<>(service.search(status, pageable).map(ShipmentResponse::summary));
    }

    /**
     * The blend on its own, for a caller that wants the numbers without the
     * composition behind them.
     */
    @GetMapping("/{id}/blend")
    public BlendResponse blend(@PathVariable Long id) {
        return BlendResponse.from(service.findById(id).blend());
    }

    @PostMapping("/{id}/items")
    public ResponseEntity<ShipmentItemResponse> addItem(
            @PathVariable Long id,
            @RequestBody @Valid ShipmentItemRequest request,
            UriComponentsBuilder uriBuilder) {

        ShipmentItem item = service.addItem(id, request);
        URI location = uriBuilder.path("/api/shipments/{id}/items/{itemId}")
                .buildAndExpand(id, item.getId()).toUri();
        return ResponseEntity.created(location).body(ShipmentItemResponse.from(item));
    }

    @PutMapping("/{id}/items/{itemId}")
    public ShipmentResponse changeItemWeight(@PathVariable Long id,
                                             @PathVariable Long itemId,
                                             @RequestBody @Valid ShipmentItemWeightRequest request) {
        return ShipmentResponse.from(service.changeItemWeight(id, itemId, request));
    }

    @DeleteMapping("/{id}/items/{itemId}")
    public ShipmentResponse removeItem(@PathVariable Long id, @PathVariable Long itemId) {
        return ShipmentResponse.from(service.removeItem(id, itemId));
    }

    @PatchMapping("/{id}/confirm")
    public ShipmentResponse confirm(@PathVariable Long id) {
        return ShipmentResponse.from(service.confirm(id));
    }

    @PatchMapping("/{id}/cancel")
    public ShipmentResponse cancel(@PathVariable Long id) {
        return ShipmentResponse.from(service.cancel(id));
    }
}
