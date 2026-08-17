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

import tech.migueldev.coffeewarehouse.api.dto.ProducerRequest;
import tech.migueldev.coffeewarehouse.api.dto.ProducerUpdateRequest;
import tech.migueldev.coffeewarehouse.domain.Producer;
import tech.migueldev.coffeewarehouse.repository.ProducerRepository;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class ProducerControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProducerRepository repository;

    @BeforeEach
    void cleanUp() {
        repository.deleteAll();
    }

    @Test
    @DisplayName("creates a producer and returns 201 pointing at the new resource")
    void createsProducer() throws Exception {
        var request = new ProducerRequest("COP-001", "Cooperativa Serra Alta", "Guaxupe", "MG");

        mockMvc.perform(post("/api/producers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(".*/api/producers/[0-9]+")))
                .andExpect(jsonPath("$.code").value("COP-001"))
                .andExpect(jsonPath("$.name").value("Cooperativa Serra Alta"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty());
    }

    @Test
    @DisplayName("stores code and state uppercase regardless of how they were typed")
    void normalizesCodeAndState() throws Exception {
        var request = new ProducerRequest("cop-002", "Fazenda Boa Vista", "Varginha", "mg");

        mockMvc.perform(post("/api/producers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("COP-002"))
                .andExpect(jsonPath("$.state").value("MG"));
    }

    @Test
    @DisplayName("rejects a code that already exists with 409")
    void rejectsDuplicateCode() throws Exception {
        repository.save(new Producer("COP-003", "Fazenda Primeira", "Patrocinio", "MG"));
        var request = new ProducerRequest("COP-003", "Fazenda Segunda", "Franca", "SP");

        mockMvc.perform(post("/api/producers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:duplicate-code"))
                .andExpect(jsonPath("$.title").value("Code already in use"));
    }

    @Test
    @DisplayName("rejects an invalid payload with 400 naming every offending field")
    void rejectsInvalidPayload() throws Exception {
        var request = new ProducerRequest("", "", "Guaxupe", "XYZ");

        mockMvc.perform(post("/api/producers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem-type:validation-failed"))
                .andExpect(jsonPath("$.errors[*].field", hasItems("code", "name", "state")));
    }

    @Test
    @DisplayName("returns 404 when the producer does not exist")
    void returnsNotFoundForUnknownId() throws Exception {
        mockMvc.perform(get("/api/producers/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value("urn:problem-type:resource-not-found"))
                .andExpect(jsonPath("$.detail").value("Producer 999999 not found"));
    }

    @Test
    @DisplayName("updates a producer and leaves its code untouched")
    void updatesProducer() throws Exception {
        Producer saved = repository.save(new Producer("COP-004", "Nome Antigo", "Guaxupe", "MG"));
        var request = new ProducerUpdateRequest("Nome Novo", "Franca", "SP");

        mockMvc.perform(put("/api/producers/{id}", saved.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COP-004"))
                .andExpect(jsonPath("$.name").value("Nome Novo"))
                .andExpect(jsonPath("$.state").value("SP"));
    }

    @Test
    @DisplayName("lists producers paginated and sorted by code")
    void listsProducersPaginated() throws Exception {
        repository.save(new Producer("COP-007", "Terceira", "Guaxupe", "MG"));
        repository.save(new Producer("COP-005", "Primeira", "Guaxupe", "MG"));
        repository.save(new Producer("COP-006", "Segunda", "Guaxupe", "MG"));

        mockMvc.perform(get("/api/producers").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].code").value("COP-005"))
                .andExpect(jsonPath("$.content[1].code").value("COP-006"))
                .andExpect(jsonPath("$.page.totalElements").value(3));
    }
}
