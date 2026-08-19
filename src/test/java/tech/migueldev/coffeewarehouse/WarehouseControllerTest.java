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

import tech.migueldev.coffeewarehouse.api.dto.WarehouseRequest;
import tech.migueldev.coffeewarehouse.api.dto.WarehouseUpdateRequest;
import tech.migueldev.coffeewarehouse.domain.Warehouse;
import tech.migueldev.coffeewarehouse.repository.WarehouseRepository;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class WarehouseControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WarehouseRepository repository;

    @Test
    @DisplayName("creates a warehouse, active by default, and returns 201")
    void createsWarehouse() throws Exception {
        var request = new WarehouseRequest("WH1", "Armazem Central", "Guaxupe", "MG");

        mockMvc.perform(post("/api/warehouses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(".*/api/warehouses/[0-9]+")))
                .andExpect(jsonPath("$.code").value("WH1"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("rejects a code that already exists with 409")
    void rejectsDuplicateCode() throws Exception {
        repository.save(new Warehouse("WH2", "Armazem Norte", "Varginha", "MG"));
        var request = new WarehouseRequest("wh2", "Outro Armazem", "Franca", "SP");

        mockMvc.perform(post("/api/warehouses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:duplicate-code"));
    }

    @Test
    @DisplayName("rejects an invalid payload with 400 naming every offending field")
    void rejectsInvalidPayload() throws Exception {
        var request = new WarehouseRequest("", "", "Guaxupe", "XYZ");

        mockMvc.perform(post("/api/warehouses")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem-type:validation-failed"))
                .andExpect(jsonPath("$.errors[*].field", hasItems("code", "name", "state")));
    }

    @Test
    @DisplayName("returns 404 when the warehouse does not exist")
    void returnsNotFoundForUnknownId() throws Exception {
        mockMvc.perform(get("/api/warehouses/{id}", 999_999))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Warehouse 999999 not found"));
    }

    @Test
    @DisplayName("updates a warehouse and leaves its code and active flag untouched")
    void updatesWarehouse() throws Exception {
        Warehouse saved = repository.save(new Warehouse("WH3", "Nome Antigo", "Guaxupe", "MG"));
        var request = new WarehouseUpdateRequest("Nome Novo", "Franca", "SP");

        mockMvc.perform(put("/api/warehouses/{id}", saved.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("WH3"))
                .andExpect(jsonPath("$.name").value("Nome Novo"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("deactivates a warehouse instead of deleting it, and can bring it back")
    void deactivatesAndReactivatesWarehouse() throws Exception {
        Warehouse saved = repository.save(new Warehouse("WH4", "Armazem Sul", "Guaxupe", "MG"));

        mockMvc.perform(patch("/api/warehouses/{id}/deactivate", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        mockMvc.perform(patch("/api/warehouses/{id}/activate", saved.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    @DisplayName("lists warehouses paginated and sorted by code")
    void listsWarehousesPaginated() throws Exception {
        repository.save(new Warehouse("WH7", "Terceiro", "Guaxupe", "MG"));
        repository.save(new Warehouse("WH5", "Primeiro", "Guaxupe", "MG"));
        repository.save(new Warehouse("WH6", "Segundo", "Guaxupe", "MG"));

        mockMvc.perform(get("/api/warehouses").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].code").value("WH5"))
                .andExpect(jsonPath("$.page.totalElements").value(3));
    }
}
