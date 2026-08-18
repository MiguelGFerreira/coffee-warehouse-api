package tech.migueldev.coffeewarehouse.api.controller;

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

@RestController
@RequestMapping("/api/warehouses")
public class WarehouseController {

    private final WarehouseService service;

    public WarehouseController(WarehouseService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<WarehouseResponse> create(@RequestBody @Valid WarehouseRequest request,
                                                    UriComponentsBuilder uriBuilder) {
        Warehouse warehouse = service.create(request);
        URI location = uriBuilder.path("/api/warehouses/{id}").buildAndExpand(warehouse.getId()).toUri();
        return ResponseEntity.created(location).body(WarehouseResponse.from(warehouse));
    }

    @GetMapping("/{id}")
    public WarehouseResponse findById(@PathVariable Long id) {
        return WarehouseResponse.from(service.findById(id));
    }

    @GetMapping
    public PagedModel<WarehouseResponse> findAll(
            @PageableDefault(size = 20, sort = "code", direction = Sort.Direction.ASC) Pageable pageable) {
        return new PagedModel<>(service.findAll(pageable).map(WarehouseResponse::from));
    }

    @PutMapping("/{id}")
    public WarehouseResponse update(@PathVariable Long id,
                                    @RequestBody @Valid WarehouseUpdateRequest request) {
        return WarehouseResponse.from(service.update(id, request));
    }

    /**
     * Taking a warehouse out of service is a state transition, not a deletion:
     * storage positions point at it and, from Phase 3 on, so does the ledger.
     */
    @PatchMapping("/{id}/deactivate")
    public WarehouseResponse deactivate(@PathVariable Long id) {
        return WarehouseResponse.from(service.deactivate(id));
    }

    @PatchMapping("/{id}/activate")
    public WarehouseResponse activate(@PathVariable Long id) {
        return WarehouseResponse.from(service.activate(id));
    }
}
