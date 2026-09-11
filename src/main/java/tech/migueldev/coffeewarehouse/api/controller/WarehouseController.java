package tech.migueldev.coffeewarehouse.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import tech.migueldev.coffeewarehouse.api.dto.WarehouseRequest;
import tech.migueldev.coffeewarehouse.api.dto.WarehouseResponse;
import tech.migueldev.coffeewarehouse.api.dto.WarehouseUpdateRequest;
import tech.migueldev.coffeewarehouse.domain.Warehouse;
import tech.migueldev.coffeewarehouse.service.WarehouseService;

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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@Tag(name = "Warehouses", description = "Physical sites, and the activation that gates their positions")
@RestController
@RequestMapping("/api/warehouses")
public class WarehouseController {

    private final WarehouseService service;

    public WarehouseController(WarehouseService service) {
        this.service = service;
    }

    @Operation(
            summary = "Register a warehouse",
            description = """
                    A warehouse is always born active. The flag moves only through the
                    dedicated activate and deactivate operations, never by editing it here.
""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created; `Location` names the warehouse"),
            @ApiResponse(responseCode = "400", description = "`validation-failed`"),
            @ApiResponse(responseCode = "409", description = "`duplicate-code`")
    })
    @PostMapping
    public ResponseEntity<WarehouseResponse> create(@RequestBody @Valid WarehouseRequest request,
                                                    UriComponentsBuilder uriBuilder) {
        Warehouse warehouse = service.create(request);
        URI location = uriBuilder.path("/api/warehouses/{id}").buildAndExpand(warehouse.getId()).toUri();
        return ResponseEntity.created(location).body(WarehouseResponse.from(warehouse));
    }

    @Operation(summary = "Read one warehouse")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The warehouse"),
            @ApiResponse(responseCode = "404", description = "`resource-not-found`")
    })
    @GetMapping("/{id}")
    public WarehouseResponse findById(@PathVariable Long id) {
        return WarehouseResponse.from(service.findById(id));
    }

    @Operation(summary = "List warehouses")
    @ApiResponse(responseCode = "200", description = "A page of warehouses")
    @GetMapping
    public PagedModel<WarehouseResponse> findAll(
            @PageableDefault(size = 20, sort = "code", direction = Sort.Direction.ASC) Pageable pageable) {
        return new PagedModel<>(service.findAll(pageable).map(WarehouseResponse::from));
    }

    @Operation(summary = "Update a warehouse's details")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated"),
            @ApiResponse(responseCode = "400", description = "`validation-failed`"),
            @ApiResponse(responseCode = "404", description = "`resource-not-found`")
    })
    @PutMapping("/{id}")
    public WarehouseResponse update(@PathVariable Long id,
                                    @RequestBody @Valid WarehouseUpdateRequest request) {
        return WarehouseResponse.from(service.update(id, request));
    }

    /**
     * Taking a warehouse out of service is a state transition, not a deletion:
     * storage positions point at it and, from Phase 3 on, so does the ledger.
     */
    @Operation(
            summary = "Take a warehouse out of service",
            description = """
                    A state transition, not a deletion: storage positions point at it, and so
                    does every movement ever recorded in them.
""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Deactivated"),
            @ApiResponse(responseCode = "404", description = "`resource-not-found`")
    })
    @PatchMapping("/{id}/deactivate")
    public WarehouseResponse deactivate(@PathVariable Long id) {
        return WarehouseResponse.from(service.deactivate(id));
    }

    @Operation(summary = "Put a warehouse back into service")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Activated"),
            @ApiResponse(responseCode = "404", description = "`resource-not-found`")
    })
    @PatchMapping("/{id}/activate")
    public WarehouseResponse activate(@PathVariable Long id) {
        return WarehouseResponse.from(service.activate(id));
    }
}
