package tech.migueldev.coffeewarehouse;

import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tech.migueldev.coffeewarehouse.api.dto.LotRequest;
import tech.migueldev.coffeewarehouse.api.dto.LotUpdateRequest;
import tech.migueldev.coffeewarehouse.domain.Lot;
import tech.migueldev.coffeewarehouse.domain.Producer;
import tech.migueldev.coffeewarehouse.repository.LotRepository;
import tech.migueldev.coffeewarehouse.repository.ProducerRepository;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

@AutoConfigureMockMvc
class LotControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LotRepository repository;

    @Autowired
    private ProducerRepository producerRepository;

    private Producer producer;

    @BeforeEach
    void seedProducer() {
        producer = producerRepository.save(new Producer("COP-001", "Cooperativa Serra Alta", "Guaxupe", "MG"));
    }

    private LotRequest lotRequest(String code) {
        return new LotRequest(code, producer.getId(), 2025, new BigDecimal("18000.000"), 300,
                new BigDecimal("11.50"), "17/18", "T6", "DURA", LocalDate.of(2025, 6, 10));
    }

    private Lot persistedLot(String code, int cropYear) {
        Lot lot = new Lot(code, producer, cropYear, new BigDecimal("18000.000"), LocalDate.of(2025, 6, 10));
        return repository.save(lot);
    }

    @Test
    @DisplayName("creates a lot awaiting allocation and returns 201")
    void createsLot() throws Exception {
        mockMvc.perform(post("/api/lots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(lotRequest("LOT-001"))))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(".*/api/lots/[0-9]+")))
                .andExpect(jsonPath("$.code").value("LOT-001"))
                .andExpect(jsonPath("$.producerCode").value("COP-001"))
                .andExpect(jsonPath("$.status").value("AWAITING_ALLOCATION"))
                .andExpect(jsonPath("$.netWeightKg").value(18000.000));
    }

    @Test
    @DisplayName("rejects a code that already exists with 409")
    void rejectsDuplicateCode() throws Exception {
        persistedLot("LOT-002", 2025);

        mockMvc.perform(post("/api/lots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(lotRequest("lot-002"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:duplicate-code"));
    }

    @Test
    @DisplayName("returns 404 when the producer of a new lot does not exist")
    void returnsNotFoundForUnknownProducer() throws Exception {
        var request = new LotRequest("LOT-003", 999_999L, 2025, new BigDecimal("18000.000"), 300,
                new BigDecimal("11.50"), "17/18", "T6", "DURA", LocalDate.of(2025, 6, 10));

        mockMvc.perform(post("/api/lots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Producer 999999 not found"));
    }

    @Test
    @DisplayName("rejects an invalid payload with 400 naming every offending field")
    void rejectsInvalidPayload() throws Exception {
        var request = new LotRequest("", null, 1800, new BigDecimal("-1.000"), 0,
                new BigDecimal("120.00"), "17/18", "T6", "DURA", LocalDate.now().plusDays(1));

        mockMvc.perform(post("/api/lots")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem-type:validation-failed"))
                .andExpect(jsonPath("$.errors[*].field", hasItems(
                        "code", "producerId", "cropYear", "netWeightKg", "bags",
                        "moisturePercent", "receivedOn")));
    }

    @Test
    @DisplayName("revises the classification and leaves weight and status untouched")
    void updatesClassification() throws Exception {
        Lot saved = persistedLot("LOT-004", 2025);
        var request = new LotUpdateRequest(280, new BigDecimal("10.80"), "18", "T4/5", "MOLE");

        mockMvc.perform(put("/api/lots/{id}", saved.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.bags").value(280))
                .andExpect(jsonPath("$.cupQuality").value("MOLE"))
                .andExpect(jsonPath("$.netWeightKg").value(18000.000))
                .andExpect(jsonPath("$.status").value("AWAITING_ALLOCATION"));
    }

    @Test
    @DisplayName("returns 404 when the lot does not exist")
    void returnsNotFoundForUnknownId() throws Exception {
        mockMvc.perform(get("/api/lots/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Lot 999999 not found"));
    }

    @Test
    @DisplayName("filters lots by crop year, by producer and by status")
    void filtersLots() throws Exception {
        Producer other = producerRepository.save(new Producer("COP-002", "Fazenda Boa Vista", "Varginha", "MG"));
        persistedLot("LOT-005", 2024);
        persistedLot("LOT-006", 2025);
        repository.save(new Lot("LOT-007", other, 2025, new BigDecimal("9000.000"), LocalDate.of(2025, 6, 10)));

        mockMvc.perform(get("/api/lots").param("cropYear", "2025"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));

        mockMvc.perform(get("/api/lots")
                        .param("cropYear", "2025")
                        .param("producerId", producer.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].code").value("LOT-006"));

        mockMvc.perform(get("/api/lots").param("status", "AWAITING_ALLOCATION"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(3)));

        mockMvc.perform(get("/api/lots").param("status", "SHIPPED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)));
    }

    @Test
    @DisplayName("lists every lot when no filter is given")
    void listsEveryLotWithoutFilters() throws Exception {
        persistedLot("LOT-008", 2024);
        persistedLot("LOT-009", 2025);

        mockMvc.perform(get("/api/lots"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].code").value("LOT-008"))
                .andExpect(jsonPath("$.page.totalElements").value(2));
    }
}
