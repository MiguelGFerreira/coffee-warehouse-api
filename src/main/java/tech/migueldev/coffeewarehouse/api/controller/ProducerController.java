package tech.migueldev.coffeewarehouse.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@Tag(name = "Producers", description = "The farms and cooperatives lots come from")
@RestController
@RequestMapping("/api/producers")
public class ProducerController {

    private final ProducerService service;

    public ProducerController(ProducerService service) {
        this.service = service;
    }

    @Operation(
            summary = "Register a producer",
            description = """
                    The `code` is the business identity of a producer: it is accepted only
                    here and never changes afterwards. It is stored uppercase, so `cop-001`
                    and `COP-001` are the same producer rather than two.
""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created; `Location` names the producer"),
            @ApiResponse(responseCode = "400", description = "`validation-failed`"),
            @ApiResponse(responseCode = "409", description = "`duplicate-code`")
    })
    @PostMapping
    public ResponseEntity<ProducerResponse> create(@RequestBody @Valid ProducerRequest request,
                                                   UriComponentsBuilder uriBuilder) {
        Producer producer = service.create(request);
        URI location = uriBuilder.path("/api/producers/{id}").buildAndExpand(producer.getId()).toUri();
        return ResponseEntity.created(location).body(ProducerResponse.from(producer));
    }

    @Operation(summary = "Read one producer")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The producer"),
            @ApiResponse(responseCode = "404", description = "`resource-not-found`")
    })
    @GetMapping("/{id}")
    public ProducerResponse findById(@PathVariable Long id) {
        return ProducerResponse.from(service.findById(id));
    }

    /**
     * Wrapped in PagedModel rather than returned as a raw Page: the JSON shape of
     * PageImpl is an implementation detail Spring Data explicitly does not commit
     * to, and serializing it directly triggers a warning for that reason.
     */
    @Operation(summary = "List producers")
    @ApiResponse(responseCode = "200", description = "A page of producers")
    @GetMapping
    public PagedModel<ProducerResponse> findAll(
            @PageableDefault(size = 20, sort = "code", direction = Sort.Direction.ASC) Pageable pageable) {
        return new PagedModel<>(service.findAll(pageable).map(ProducerResponse::from));
    }

    @Operation(
            summary = "Update a producer's details",
            description = """
                    Name, city and state. The code is absent because it is immutable.
""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated"),
            @ApiResponse(responseCode = "400", description = "`validation-failed`"),
            @ApiResponse(responseCode = "404", description = "`resource-not-found`")
    })
    @PutMapping("/{id}")
    public ProducerResponse update(@PathVariable Long id,
                                   @RequestBody @Valid ProducerUpdateRequest request) {
        return ProducerResponse.from(service.update(id, request));
    }
}
