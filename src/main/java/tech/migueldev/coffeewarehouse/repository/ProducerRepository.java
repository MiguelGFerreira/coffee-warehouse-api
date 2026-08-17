package tech.migueldev.coffeewarehouse.repository;

import tech.migueldev.coffeewarehouse.domain.Producer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProducerRepository extends JpaRepository<Producer, Long> {

    Optional<Producer> findByCode(String code);

    boolean existsByCode(String code);
}
