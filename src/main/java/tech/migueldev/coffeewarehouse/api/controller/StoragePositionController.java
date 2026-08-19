package tech.migueldev.coffeewarehouse.api.controller;

import tech.migueldev.coffeewarehouse.api.dto.StoragePositionRequest;
import tech.migueldev.coffeewarehouse.api.dto.StoragePositionResponse;
import tech.migueldev.coffeewarehouse.api.dto.StoragePositionUpdateRequest;
import tech.migueldev.coffeewarehouse.domain.StoragePosition;
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

@RestController
@RequestMapping("/api/storage-positions")
public class StoragePositionController {

    private final StoragePositionService service;

    public StoragePositionController(StoragePositionService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<StoragePositionResponse> create(
            @RequestBody @Valid StoragePositionRequest request,
            UriComponentsBuilder uriBuilder) {

        StoragePosition position = service.create(request);
        URI location = uriBuilder.path("/api/storage-positions/{id}")
                .buildAndExpand(position.getId()).toUri();
        return ResponseEntity.created(location).body(StoragePositionResponse.from(position));
    }

    @GetMapping("/{id}")
    public StoragePositionResponse findById(@PathVariable Long id) {
        return StoragePositionResponse.from(service.findById(id));
    }

    @GetMapping
    public PagedModel<StoragePositionResponse> search(
            @RequestParam(required = false) Long warehouseId,
            @RequestParam(required = false) Boolean active,
            @PageableDefault(size = 20, sort = "code", direction = Sort.Direction.ASC) Pageable pageable) {

        return new PagedModel<>(service.search(warehouseId, active, pageable)
                .map(StoragePositionResponse::from));
    }

    @PutMapping("/{id}")
    public StoragePositionResponse update(@PathVariable Long id,
                                          @RequestBody @Valid StoragePositionUpdateRequest request) {
        return StoragePositionResponse.from(service.update(id, request));
    }

    @PatchMapping("/{id}/deactivate")
    public StoragePositionResponse deactivate(@PathVariable Long id) {
        return StoragePositionResponse.from(service.deactivate(id));
    }

    @PatchMapping("/{id}/activate")
    public StoragePositionResponse activate(@PathVariable Long id) {
        return StoragePositionResponse.from(service.activate(id));
    }
}
