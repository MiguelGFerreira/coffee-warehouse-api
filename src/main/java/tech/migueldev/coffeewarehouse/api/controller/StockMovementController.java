package tech.migueldev.coffeewarehouse.api.controller;

import tech.migueldev.coffeewarehouse.api.dto.InboundRequest;
import tech.migueldev.coffeewarehouse.api.dto.OutboundRequest;
import tech.migueldev.coffeewarehouse.api.dto.StockMovementResponse;
import tech.migueldev.coffeewarehouse.api.dto.TransferRequest;
import tech.migueldev.coffeewarehouse.domain.StockMovement;
import tech.migueldev.coffeewarehouse.service.StockMovementService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

/**
 * Three endpoints rather than one with a type discriminator, so each request
 * record carries exactly the fields its operation has: an inbound has no
 * source, an outbound has no target. A single endpoint would have to accept
 * both and reject the combinations at runtime.
 *
 * There is no PUT and no DELETE here, and there never will be. The ledger is
 * append-only; a movement recorded in error is corrected by recording its
 * opposite, which is what leaves an audit trail worth having.
 */
@RestController
@RequestMapping("/api/movements")
public class StockMovementController {

    private final StockMovementService service;

    public StockMovementController(StockMovementService service) {
        this.service = service;
    }

    @PostMapping("/inbound")
    public ResponseEntity<StockMovementResponse> recordInbound(@RequestBody @Valid InboundRequest request,
                                                               UriComponentsBuilder uriBuilder) {
        return created(service.recordInbound(request), uriBuilder);
    }

    @PostMapping("/transfers")
    public ResponseEntity<StockMovementResponse> recordTransfer(@RequestBody @Valid TransferRequest request,
                                                                UriComponentsBuilder uriBuilder) {
        return created(service.recordTransfer(request), uriBuilder);
    }

    @PostMapping("/outbound")
    public ResponseEntity<StockMovementResponse> recordOutbound(@RequestBody @Valid OutboundRequest request,
                                                                UriComponentsBuilder uriBuilder) {
        return created(service.recordOutbound(request), uriBuilder);
    }

    @GetMapping("/{id}")
    public StockMovementResponse findById(@PathVariable Long id) {
        return StockMovementResponse.from(service.findById(id));
    }

    private ResponseEntity<StockMovementResponse> created(StockMovement movement,
                                                          UriComponentsBuilder uriBuilder) {
        URI location = uriBuilder.path("/api/movements/{id}").buildAndExpand(movement.getId()).toUri();
        return ResponseEntity.created(location).body(StockMovementResponse.from(movement));
    }
}
