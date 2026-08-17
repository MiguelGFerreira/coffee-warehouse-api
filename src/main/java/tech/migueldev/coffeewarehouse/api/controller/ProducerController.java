package tech.migueldev.coffeewarehouse.api.controller;

import tech.migueldev.coffeewarehouse.api.dto.ProducerRequest;
import tech.migueldev.coffeewarehouse.api.dto.ProducerResponse;
import tech.migueldev.coffeewarehouse.api.dto.ProducerUpdateRequest;
import tech.migueldev.coffeewarehouse.domain.Producer;
import tech.migueldev.coffeewarehouse.service.ProducerService;

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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;

@RestController
@RequestMapping("/api/producers")
public class ProducerController {

    private final ProducerService service;

    public ProducerController(ProducerService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<ProducerResponse> create(@RequestBody @Valid ProducerRequest request,
                                                   UriComponentsBuilder uriBuilder) {
        Producer producer = service.create(request);
        URI location = uriBuilder.path("/api/producers/{id}").buildAndExpand(producer.getId()).toUri();
        return ResponseEntity.created(location).body(ProducerResponse.from(producer));
    }

    @GetMapping("/{id}")
    public ProducerResponse findById(@PathVariable Long id) {
        return ProducerResponse.from(service.findById(id));
    }

    /**
     * Wrapped in PagedModel rather than returned as a raw Page: the JSON shape of
     * PageImpl is an implementation detail Spring Data explicitly does not commit
     * to, and serializing it directly triggers a warning for that reason.
     */
    @GetMapping
    public PagedModel<ProducerResponse> findAll(
            @PageableDefault(size = 20, sort = "code", direction = Sort.Direction.ASC) Pageable pageable) {
        return new PagedModel<>(service.findAll(pageable).map(ProducerResponse::from));
    }

    @PutMapping("/{id}")
    public ProducerResponse update(@PathVariable Long id,
                                   @RequestBody @Valid ProducerUpdateRequest request) {
        return ProducerResponse.from(service.update(id, request));
    }
}
