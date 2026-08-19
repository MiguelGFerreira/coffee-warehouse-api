package tech.migueldev.coffeewarehouse;

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tech.migueldev.coffeewarehouse.api.dto.StoragePositionRequest;
import tech.migueldev.coffeewarehouse.api.dto.StoragePositionUpdateRequest;
import tech.migueldev.coffeewarehouse.domain.StoragePosition;
import tech.migueldev.coffeewarehouse.domain.Warehouse;
import tech.migueldev.coffeewarehouse.repository.StoragePositionRepository;
import tech.migueldev.coffeewarehouse.repository.WarehouseRepository;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;

@AutoConfigureMockMvc
class StoragePositionControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StoragePositionRepository repository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    private Warehouse warehouse;

    @BeforeEach
    void seedWarehouse() {
        warehouse = warehouseRepository.save(new Warehouse("WH1", "Armazem Central", "Guaxupe", "MG"));
    }

    @Test
    @DisplayName("builds the composite code from the warehouse and the address")
    void buildsCompositeCode() throws Exception {
        var request = new StoragePositionRequest(warehouse.getId(), "3", "12", "2",
                new BigDecimal("60000.000"));

        mockMvc.perform(post("/api/storage-positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(".*/api/storage-positions/[0-9]+")))
                .andExpect(jsonPath("$.code").value("WH1-A03-B12-L02"))
                .andExpect(jsonPath("$.warehouseCode").value("WH1"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("treats aisle 3 and aisle 03 as the same address")
    void padsAddressComponentsConsistently() throws Exception {
        repository.save(new StoragePosition(warehouse, "03", "12", "02", new BigDecimal("60000.000")));
        var request = new StoragePositionRequest(warehouse.getId(), "3", "12", "2",
                new BigDecimal("45000.000"));

        mockMvc.perform(post("/api/storage-positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:duplicate-code"));
    }

    @Test
    @DisplayName("returns 404 when the warehouse of a new position does not exist")
    void returnsNotFoundForUnknownWarehouse() throws Exception {
        var request = new StoragePositionRequest(999_999L, "01", "01", "01",
                new BigDecimal("1000.000"));

        mockMvc.perform(post("/api/storage-positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Warehouse 999999 not found"));
    }

    @Test
    @DisplayName("rejects an invalid payload with 400 naming every offending field")
    void rejectsInvalidPayload() throws Exception {
        var request = new StoragePositionRequest(null, "", "12", "2", new BigDecimal("-1.000"));

        mockMvc.perform(post("/api/storage-positions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[*].field",
                        hasItems("warehouseId", "aisle", "capacityKg")));
    }

    @Test
    @DisplayName("revises the capacity and leaves the address untouched")
    void updatesCapacity() throws Exception {
        StoragePosition saved = repository.save(
                new StoragePosition(warehouse, "04", "01", "01", new BigDecimal("60000.000")));
        var request = new StoragePositionUpdateRequest(new BigDecimal("75000.000"));

        mockMvc.perform(put("/api/storage-positions/{id}", saved.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("WH1-A04-B01-L01"))
                .andExpect(jsonPath("$.capacityKg").value(75000.000));
    }

    @Test
    @DisplayName("deactivates a position instead of deleting it")
    void deactivatesPosition() throws Exception {
        StoragePosition saved = repository.save(
                new StoragePosition(warehouse, "05", "01", "01", new BigDecimal("60000.000")));

        mockMvc.perform(patch("/api/storage-positions/{id}/deactivate", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(patch("/api/storage-positions/{id}/activate", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("filters positions by warehouse and by active flag")
    void filtersByWarehouseAndActive() throws Exception {
        Warehouse other = warehouseRepository.save(new Warehouse("WH2", "Armazem Norte", "Varginha", "MG"));
        repository.save(new StoragePosition(warehouse, "06", "01", "01", new BigDecimal("1000.000")));
        StoragePosition inactive = new StoragePosition(warehouse, "07", "01", "01", new BigDecimal("1000.000"));
        inactive.deactivate();
        repository.save(inactive);
        repository.save(new StoragePosition(other, "01", "01", "01", new BigDecimal("1000.000")));

        mockMvc.perform(get("/api/storage-positions").param("warehouseId", warehouse.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));

        mockMvc.perform(get("/api/storage-positions")
                        .param("warehouseId", warehouse.getId().toString())
                        .param("active", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].code").value("WH1-A06-B01-L01"));
    }
}
