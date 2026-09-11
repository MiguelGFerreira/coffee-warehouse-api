package tech.migueldev.coffeewarehouse.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;

import tech.migueldev.coffeewarehouse.api.dto.LotRequest;
import tech.migueldev.coffeewarehouse.api.dto.LotResponse;
import tech.migueldev.coffeewarehouse.api.dto.LotStatementResponse;
import tech.migueldev.coffeewarehouse.api.dto.LotUpdateRequest;
import tech.migueldev.coffeewarehouse.domain.Lot;
import tech.migueldev.coffeewarehouse.domain.LotStatus;
import tech.migueldev.coffeewarehouse.service.LotService;
import tech.migueldev.coffeewarehouse.service.StockMovementService;

import jakarta.validation.Valid;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@Tag(name = "Lots", description = "Coffee received from a producer, its classification and its statement")
@RestController
@RequestMapping("/api/lots")
public class LotController {

    private final LotService service;
    private final StockMovementService movementService;

    public LotController(LotService service, StockMovementService movementService) {
        this.service = service;
        this.movementService = movementService;
    }

    @Operation(
            summary = "Register a lot received from a producer",
            description = """
                    A lot enters as `AWAITING_ALLOCATION` and has no weight anywhere yet: \
                    `netWeightKg` is what the producer delivered, not what is in the \
                    warehouse. It gets stored by recording an inbound movement.

                    There is no status field in the payload on purpose. Status moves with \
                    the ledger, and letting a client post one would make the column and \
                    the movement history disagree.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created; `Location` names the lot"),
            @ApiResponse(responseCode = "400", description = "`validation-failed`"),
            @ApiResponse(responseCode = "404", description = "`resource-not-found` -- unknown producer"),
            @ApiResponse(responseCode = "409", description = "`duplicate-code`")
    })
    @PostMapping
    public ResponseEntity<LotResponse> create(@RequestBody @Valid LotRequest request,
                                              UriComponentsBuilder uriBuilder) {
        Lot lot = service.create(request);
        URI location = uriBuilder.path("/api/lots/{id}").buildAndExpand(lot.getId()).toUri();
        return ResponseEntity.created(location).body(LotResponse.from(lot));
    }

    @Operation(summary = "Read one lot")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The lot"),
            @ApiResponse(responseCode = "404", description = "`resource-not-found`")
    })
    @GetMapping("/{id}")
    public LotResponse findById(@PathVariable Long id) {
        return LotResponse.from(service.findById(id));
    }

    /**
     * The three filters the roadmap asks for, all optional and combinable.
     * An unknown status value is rejected by Spring before reaching the service.
     */
    @Operation(
            summary = "List lots, filtered and paged",
            description = """
                    All three filters are optional and combine. An unrecognised `status` is refused with 400 rather than quietly ignored.""")
    @ApiResponse(responseCode = "200", description = "A page of lots")
    @GetMapping
    public PagedModel<LotResponse> search(
            @RequestParam(required = false) LotStatus status,
            @RequestParam(required = false) Integer cropYear,
            @RequestParam(required = false) Long producerId,
            @PageableDefault(size = 20, sort = "code", direction = Sort.Direction.ASC) Pageable pageable) {

        return new PagedModel<>(service.search(status, cropYear, producerId, pageable)
                .map(LotResponse::from));
    }

    /**
     * The history of the lot and the balance that history produces.
     *
     * The balance is not a stored number being reported back: it is the sum of
     * the entries listed underneath it, so the statement can be checked by hand.
     */
    @Operation(
            summary = "The movement history of a lot, and the balance it produces",
            description = """
                    `storedWeightKg` is not a stored number being read back: it is the sum of the entries listed underneath it, which is what makes the statement checkable by hand.

                    Entries are ordered by `occurredAt`. Because the ledger refuses a movement backdated behind existing history for the same lot and position, that ordering is also the order the stock actually moved -- so a running total taken down the list never dips below zero.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The statement"),
            @ApiResponse(responseCode = "404", description = "`resource-not-found`")
    })
    @GetMapping("/{id}/statement")
    public LotStatementResponse statement(@PathVariable Long id) {
        Lot lot = service.findById(id);
        return LotStatementResponse.of(lot,
                movementService.balanceOfLot(id),
                movementService.statementOf(id));
    }

    /**
     * Classification only. Status is absent on purpose: it moves with the
     * movement ledger in Phase 3, never by direct edit.
     */
    @Operation(
            summary = "Revise a lot's classification",
            description = """
                    Bags, moisture, screen size, defect type and cup quality -- the things a re-graded sample genuinely changes.

                    Weight, crop year, producer and receiving date are absent because they are fixed at creation, and status is absent because it belongs to the ledger. Note that revising moisture moves the blend of any **draft** shipment holding this lot; a confirmed one keeps the number frozen at the moment it was dispatched.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated"),
            @ApiResponse(responseCode = "400", description = "`validation-failed`"),
            @ApiResponse(responseCode = "404", description = "`resource-not-found`")
    })
    @PutMapping("/{id}")
    public LotResponse update(@PathVariable Long id, @RequestBody @Valid LotUpdateRequest request) {
        return LotResponse.from(service.updateClassification(id, request));
    }
}
