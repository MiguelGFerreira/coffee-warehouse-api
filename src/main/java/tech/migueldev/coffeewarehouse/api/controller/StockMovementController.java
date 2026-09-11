package tech.migueldev.coffeewarehouse.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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
@Tag(name = "Stock movements", description = "The append-only ledger every balance is derived from")
@RestController
@RequestMapping("/api/movements")
public class StockMovementController {

    private final StockMovementService service;

    public StockMovementController(StockMovementService service) {
        this.service = service;
    }

    @Operation(
            summary = "Receive weight into a position",
            description = """
                    Puts coffee away. The first inbound for a lot moves it from \
                    `AWAITING_ALLOCATION` to `STORED`; later ones leave the status alone, \
                    so a lot can be put away in parts across several positions.

                    Two ceilings are checked, one per aggregate, and both are sums over \
                    the ledger rather than stored counters:

                    - the **position** cannot end up holding more than its `capacityKg`;
                    - the **lot** cannot have more received across its whole history than \
                      its `netWeightKg`. The cap is on cumulative inbound, not on the \
                      current balance -- a lot that shipped out entirely has a balance of \
                      zero, but that coffee is gone and does not become receivable again.

                    `occurredAt` is optional and defaults to now. A past instant is \
                    accepted, but never one behind a movement already recorded for the \
                    same lot at the same position.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Recorded; `Location` names the movement"),
            @ApiResponse(responseCode = "400", description = "`validation-failed`"),
            @ApiResponse(responseCode = "404", description = "`resource-not-found` -- unknown lot or position"),
            @ApiResponse(responseCode = "409", description = """
                    `capacity-exceeded` (the position has no room), \
                    `lot-weight-exceeded` (more than the producer delivered), \
                    `position-not-available` (the position is inactive), \
                    `lot-not-movable` (the lot is SHIPPED), \
                    `out-of-order-movement` (backdated behind existing history), or \
                    `concurrent-modification` (someone filled the position first -- retry)""")
    })
    @PostMapping("/inbound")
    public ResponseEntity<StockMovementResponse> recordInbound(@RequestBody @Valid InboundRequest request,
                                                               UriComponentsBuilder uriBuilder) {
        return created(service.recordInbound(request), uriBuilder);
    }

    @Operation(
            summary = "Move weight from one position to another",
            description = """
                    One row, two endpoints: it subtracts from the source and adds to the \
                    target, which is why the total stored in the warehouse is unchanged \
                    while both positions' occupancy moves.

                    The source must actually hold that much of that lot, and the target \
                    must have room for it. Both positions are locked in ascending id \
                    order, so two transfers running in opposite directions between the \
                    same pair cannot deadlock.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Recorded"),
            @ApiResponse(responseCode = "400", description = """
                    `validation-failed`, or `invalid-movement` when the source and \
                    target are the same position"""),
            @ApiResponse(responseCode = "404", description = "`resource-not-found`"),
            @ApiResponse(responseCode = "409", description = """
                    `insufficient-balance` (the source does not hold that much), \
                    `capacity-exceeded`, `position-not-available`, `lot-not-movable`, \
                    `out-of-order-movement` or `concurrent-modification`""")
    })
    @PostMapping("/transfers")
    public ResponseEntity<StockMovementResponse> recordTransfer(@RequestBody @Valid TransferRequest request,
                                                                UriComponentsBuilder uriBuilder) {
        return created(service.recordTransfer(request), uriBuilder);
    }

    @Operation(
            summary = "Send weight out of a position",
            description = """
                    Weight only leaves, so there is no capacity check -- but the source \
                    must hold it.

                    **The lot's status is not touched here.** A lot becomes `SHIPPED` \
                    when a shipment carrying it is confirmed and nothing of it remains, \
                    which is the shipment's job. Having two places write that state would \
                    be one too many. Confirming a shipment routes every line through this \
                    same operation rather than writing to the ledger directly.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Recorded"),
            @ApiResponse(responseCode = "400", description = "`validation-failed`"),
            @ApiResponse(responseCode = "404", description = "`resource-not-found`"),
            @ApiResponse(responseCode = "409", description = """
                    `insufficient-balance`, `lot-not-movable`, `out-of-order-movement` \
                    or `concurrent-modification`""")
    })
    @PostMapping("/outbound")
    public ResponseEntity<StockMovementResponse> recordOutbound(@RequestBody @Valid OutboundRequest request,
                                                                UriComponentsBuilder uriBuilder) {
        return created(service.recordOutbound(request), uriBuilder);
    }

    @Operation(
            summary = "Read one ledger entry",
            description = """
                    Movements are immutable. There is no `PUT` and no `DELETE` here, and \
                    the database refuses both outright -- a movement recorded in error is \
                    corrected by recording its opposite, which is what leaves an audit \
                    trail worth having.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The movement"),
            @ApiResponse(responseCode = "404", description = "`resource-not-found`")
    })
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
