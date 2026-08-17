package tech.migueldev.coffeewarehouse.service;

import tech.migueldev.coffeewarehouse.api.dto.ProducerRequest;
import tech.migueldev.coffeewarehouse.api.dto.ProducerUpdateRequest;
import tech.migueldev.coffeewarehouse.api.exception.DuplicateCodeException;
import tech.migueldev.coffeewarehouse.api.exception.ResourceNotFoundException;
import tech.migueldev.coffeewarehouse.domain.Producer;
import tech.migueldev.coffeewarehouse.repository.ProducerRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProducerService {

    private final ProducerRepository repository;

    public ProducerService(ProducerRepository repository) {
        this.repository = repository;
    }

    /**
     * The duplicate check exists to return a clean 409 instead of leaking a
     * constraint violation. It does not make the operation safe on its own --
     * the unique index is what actually guarantees uniqueness, and the handler
     * maps its violation to the same 409.
     */
    @Transactional
    public Producer create(ProducerRequest request) {
        Producer producer = new Producer(request.code(), request.name(), request.city(), request.state());
        if (repository.existsByCode(producer.getCode())) {
            throw DuplicateCodeException.of("Producer", producer.getCode());
        }
        return repository.save(producer);
    }

    @Transactional
    public Producer update(Long id, ProducerUpdateRequest request) {
        Producer producer = findById(id);
        producer.updateDetails(request.name(), request.city(), request.state());
        return producer;
    }

    @Transactional(readOnly = true)
    public Producer findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Producer", id));
    }

    @Transactional(readOnly = true)
    public Page<Producer> findAll(Pageable pageable) {
        return repository.findAll(pageable);
    }
}
