package tech.migueldev.coffeewarehouse.service;

import tech.migueldev.coffeewarehouse.api.dto.LotRequest;
import tech.migueldev.coffeewarehouse.api.dto.LotUpdateRequest;
import tech.migueldev.coffeewarehouse.api.exception.DuplicateCodeException;
import tech.migueldev.coffeewarehouse.api.exception.ResourceNotFoundException;
import tech.migueldev.coffeewarehouse.domain.Lot;
import tech.migueldev.coffeewarehouse.domain.LotStatus;
import tech.migueldev.coffeewarehouse.domain.Producer;
import tech.migueldev.coffeewarehouse.repository.LotRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LotService {

    private final LotRepository repository;
    private final ProducerService producerService;

    public LotService(LotRepository repository, ProducerService producerService) {
        this.repository = repository;
        this.producerService = producerService;
    }

    @Transactional
    public Lot create(LotRequest request) {
        Producer producer = producerService.findById(request.producerId());
        Lot lot = new Lot(request.code(), producer, request.cropYear(), request.netWeightKg(),
                request.receivedOn());
        lot.updateClassification(request.bags(), request.moisturePercent(), request.screenSize(),
                request.defectType(), request.cupQuality());

        if (repository.existsByCode(lot.getCode())) {
            throw DuplicateCodeException.of("Lot", lot.getCode());
        }
        return repository.save(lot);
    }

    @Transactional
    public Lot updateClassification(Long id, LotUpdateRequest request) {
        Lot lot = findById(id);
        lot.updateClassification(request.bags(), request.moisturePercent(), request.screenSize(),
                request.defectType(), request.cupQuality());
        return lot;
    }

    @Transactional(readOnly = true)
    public Lot findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> ResourceNotFoundException.of("Lot", id));
    }

    @Transactional(readOnly = true)
    public Page<Lot> search(LotStatus status, Integer cropYear, Long producerId, Pageable pageable) {
        return repository.search(status, cropYear, producerId, pageable);
    }
}
