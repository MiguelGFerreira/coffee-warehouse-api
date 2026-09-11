package tech.migueldev.coffeewarehouse.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import tech.migueldev.coffeewarehouse.api.dto.StoragePositionRequest;
import tech.migueldev.coffeewarehouse.api.dto.PositionOccupancyResponse;
import tech.migueldev.coffeewarehouse.api.dto.StoragePositionResponse;
import tech.migueldev.coffeewarehouse.api.dto.StoragePositionUpdateRequest;
import tech.migueldev.coffeewarehouse.domain.StoragePosition;
import tech.migueldev.coffeewarehouse.service.StockMovementService;
import tech.migueldev.coffeewarehouse.service.StoragePositionService;

import jakarta.validation.Valid;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
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

@Tag(name = "Storage positions", description = "Addressable bins inside a warehouse, and what they hold")
@RestController
@RequestMapping("/api/storage-positions")
public class StoragePositionController {

    private final StoragePositionService service;
    private final StockMovementService movementService;

    public StoragePositionController(StoragePositionService service,
                                     StockMovementService movementService) {
        this.service = service;
        this.movementService = movementService;
    }

    @Operation(
            summary = "Create an addressable position in a warehouse",
            description = """
                    The composite code is derived, not supplied: warehouse, aisle, bay \
                    and level become something like `WH1-A03-B12-L02`. Accepting a code \
                    from the caller would let it disagree with the address it describes.

                    Numeric components are padded to two digits, so aisle `3` and aisle \
                    `03` are the same position rather than two.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created; `Location` names the position"),
            @ApiResponse(responseCode = "400", description = "`validation-failed`"),
            @ApiResponse(responseCode = "404", description = "`resource-not-found` -- unknown warehouse"),
            @ApiResponse(responseCode = "409", description = "`duplicate-code` -- that address already exists")
    })
    @PostMapping
    public ResponseEntity<StoragePositionResponse> create(
            @RequestBody @Valid StoragePositionRequest request,
            UriComponentsBuilder uriBuilder) {

        StoragePosition position = service.create(request);
        URI location = uriBuilder.path("/api/storage-positions/{id}")
                .buildAndExpand(position.getId()).toUri();
        return ResponseEntity.created(location).body(StoragePositionResponse.from(position));
    }

    @Operation(summary = "Read one position")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The position"),
            @ApiResponse(responseCode = "404", description = "`resource-not-found`")
    })
    @GetMapping("/{id}")
    public StoragePositionResponse findById(@PathVariable Long id) {
        return StoragePositionResponse.from(service.findById(id));
    }

    @Operation(summary = "List positions, filtered by warehouse or activation")
    @ApiResponse(responseCode = "200", description = "A page of positions")
    @GetMapping
    public PagedModel<StoragePositionResponse> search(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Boolean active,
            @PageableDefault(size = 20, sort = "code", direction = Sort.Direction.ASC) Pageable pageable) {

        return new PagedModel<>(service.search(warehouseId, active, pageable)
                .map(StoragePositionResponse::from));
    }

    /**
     * How full the position is right now, aggregated from the ledger. There is
     * no occupancy column to read; there is only the history.
     */
    @Operation(
            summary = "How full the position is",
            description = """
                    `occupiedKg` is summed from the ledger every time it is asked. There \
                    is no occupancy column to read -- there is only the history, which is \
                    the whole point of the design: a stored figure could drift from the \
                    movements behind it, and this one cannot.

                    `availableKg` is `capacityKg - occupiedKg` and is never negative, \
                    because a re-rating below current occupancy is refused.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Capacity, occupancy and what is left"),
            @ApiResponse(responseCode = "404", description = "`resource-not-found`")
    })
    @GetMapping("/{id}/occupancy")
    public PositionOccupancyResponse occupancy(@PathVariable Long id) {
        StoragePosition position = service.findById(id);
        return PositionOccupancyResponse.of(position, movementService.occupancyOf(id));
    }

    @Operation(
            summary = "Re-rate a position's capacity",
            description = """
                    **Cannot fall below what the position already holds.** Without that \
                    rule this endpoint could do what no movement is allowed to do -- a \
                    position holding 12,000 kg re-rated to 100 kg would then report a \
                    negative `availableKg`. It raises the same `capacity-exceeded` \
                    problem type an over-filled inbound does, because it is the same \
                    invariant seen from the other side.

                    Shrinking to exactly the current occupancy is allowed: the rule is \
                    "not below what is stored", not "never smaller".""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Re-rated"),
            @ApiResponse(responseCode = "400", description = "`validation-failed`"),
            @ApiResponse(responseCode = "404", description = "`resource-not-found`"),
            @ApiResponse(responseCode = "409", description = """
                    `capacity-exceeded` (below what is stored) or \
                    `concurrent-modification` (an inbound filled it first -- retry)""")
    })
    @PutMapping("/{id}")
    public StoragePositionResponse update(@PathVariable Long id,
                                          @RequestBody @Valid StoragePositionUpdateRequest request) {
        return StoragePositionResponse.from(service.update(id, request));
    }

    @Operation(
            summary = "Take a position out of service",
            description = """
                    An inactive position refuses incoming stock with \
                    `position-not-available`. Whatever it already holds stays where it is \
                    and can still be transferred out -- deactivating a bin is not a way \
                    to make its contents disappear.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deactivated"),
            @ApiResponse(responseCode = "404", description = "`resource-not-found`")
    })
    @PatchMapping("/{id}/deactivate")
    public StoragePositionResponse deactivate(@PathVariable Long id) {
        return StoragePositionResponse.from(service.deactivate(id));
    }

    @Operation(summary = "Put a position back into service")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Activated"),
            @ApiResponse(responseCode = "404", description = "`resource-not-found`")
    })
    @PatchMapping("/{id}/activate")
    public StoragePositionResponse activate(@PathVariable Long id) {
        return StoragePositionResponse.from(service.activate(id));
    }
}
