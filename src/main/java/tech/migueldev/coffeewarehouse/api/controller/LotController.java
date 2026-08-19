package tech.migueldev.coffeewarehouse.api.controller;

import tech.migueldev.coffeewarehouse.api.dto.LotRequest;
import tech.migueldev.coffeewarehouse.api.dto.LotResponse;
import tech.migueldev.coffeewarehouse.api.dto.LotUpdateRequest;
import tech.migueldev.coffeewarehouse.domain.Lot;
import tech.migueldev.coffeewarehouse.domain.LotStatus;
import tech.migueldev.coffeewarehouse.service.LotService;

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

@RestController
@RequestMapping("/api/lots")
public class LotController {

    private final LotService service;

    public LotController(LotService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<LotResponse> create(@RequestBody @Valid LotRequest request,
                                              UriComponentsBuilder uriBuilder) {
        Lot lot = service.create(request);
        URI location = uriBuilder.path("/api/lots/{id}").buildAndExpand(lot.getId()).toUri();
        return ResponseEntity.created(location).body(LotResponse.from(lot));
    }

    @GetMapping("/{id}")
    public LotResponse findById(@PathVariable Long id) {
        return LotResponse.from(service.findById(id));
    }

    /**
     * The three filters the roadmap asks for, all optional and combinable.
     * An unknown status value is rejected by Spring before reaching the service.
     */
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
     * Classification only. Status is absent on purpose: it moves with the
     * movement ledger in Phase 3, never by direct edit.
     */
    @PutMapping("/{id}")
    public LotResponse update(@PathVariable Long id, @RequestBody @Valid LotUpdateRequest request) {
        return LotResponse.from(service.updateClassification(id, request));
    }
}
