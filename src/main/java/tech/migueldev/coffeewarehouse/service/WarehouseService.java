package tech.migueldev.coffeewarehouse.service;

import tech.migueldev.coffeewarehouse.api.dto.WarehouseRequest;
import tech.migueldev.coffeewarehouse.api.dto.WarehouseUpdateRequest;
import tech.migueldev.coffeewarehouse.api.exception.DuplicateCodeException;
import tech.migueldev.coffeewarehouse.api.exception.ResourceNotFoundException;
import tech.migueldev.coffeewarehouse.domain.Warehouse;
import tech.migueldev.coffeewarehouse.repository.WarehouseRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WarehouseService {

    private final WarehouseRepository repository;

    public WarehouseService(WarehouseRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public Warehouse create(WarehouseRequest request) {
        Warehouse warehouse = new Warehouse(request.code(), request.name(), request.city(), request.state());
        if (repository.existsByCode(warehouse.getCode())) {
            throw DuplicateCodeException.of("Warehouse", warehouse.getCode());
        }
        return repository.save(warehouse);
    }

    @Transactional
    public Warehouse update(Long id, WarehouseUpdateRequest request) {
        Warehouse warehouse = findById(id);
        warehouse.updateDetails(request.name(), request.city(), request.state());
        return warehouse;
    }

    /**
     * Deactivating is idempotent on purpose: the caller wants the warehouse out
     * of service, and asking twice is not an error worth a 409.
     */
    @Transactional
    public Warehouse deactivate(Long id) {
        Warehouse warehouse = findById(id);
        warehouse.deactivate();
        return warehouse;
    }

    @Transactional
    public Warehouse activate(Long id) {
        Warehouse warehouse = findById(id);
        warehouse.activate();
        return warehouse;
    }

    @Transactional(readOnly = true)
    public Warehouse findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Warehouse", id));
    }

    @Transactional(readOnly = true)
    public Page<Warehouse> findAll(Pageable pageable) {
        return repository.findAll(pageable);
    }
}
