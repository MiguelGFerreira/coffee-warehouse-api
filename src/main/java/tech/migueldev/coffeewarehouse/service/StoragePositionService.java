package tech.migueldev.coffeewarehouse.service;

import tech.migueldev.coffeewarehouse.api.dto.StoragePositionRequest;
import tech.migueldev.coffeewarehouse.api.dto.StoragePositionUpdateRequest;
import tech.migueldev.coffeewarehouse.api.exception.DuplicateCodeException;
import tech.migueldev.coffeewarehouse.api.exception.ResourceNotFoundException;
import tech.migueldev.coffeewarehouse.domain.StoragePosition;
import tech.migueldev.coffeewarehouse.domain.Warehouse;
import tech.migueldev.coffeewarehouse.repository.StoragePositionRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StoragePositionService {

    private final StoragePositionRepository repository;
    private final WarehouseService warehouseService;

    public StoragePositionService(StoragePositionRepository repository,
                                  WarehouseService warehouseService) {
        this.repository = repository;
        this.warehouseService = warehouseService;
    }

    /**
     * Resolving the warehouse through its service rather than its repository
     * keeps the 404 message for a missing warehouse identical no matter which
     * endpoint the caller came in through.
     */
    @Transactional
    public StoragePosition create(StoragePositionRequest request) {
        Warehouse warehouse = warehouseService.findById(request.warehouseId());
        StoragePosition position = new StoragePosition(warehouse, request.aisle(), request.bay(),
                request.level(), request.capacityKg());

        if (repository.existsByCode(position.getCode())) {
            throw DuplicateCodeException.of("Storage position", position.getCode());
        }
        return repository.save(position);
    }

    @Transactional
    public StoragePosition update(Long id, StoragePositionUpdateRequest request) {
        StoragePosition position = findById(id);
        position.changeCapacity(request.capacityKg());
        return position;
    }

    @Transactional
    public StoragePosition deactivate(Long id) {
        StoragePosition position = findById(id);
        position.deactivate();
        return position;
    }

    @Transactional
    public StoragePosition activate(Long id) {
        StoragePosition position = findById(id);
        position.activate();
        return position;
    }

    @Transactional(readOnly = true)
    public StoragePosition findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Storage position", id));
    }

    @Transactional(readOnly = true)
    public Page<StoragePosition> search(Long warehouseId, Boolean active, Pageable pageable) {
        return repository.search(warehouseId, active, pageable);
    }
}
