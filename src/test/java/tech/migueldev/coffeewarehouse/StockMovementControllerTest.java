package tech.migueldev.coffeewarehouse;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tech.migueldev.coffeewarehouse.api.dto.InboundRequest;
import tech.migueldev.coffeewarehouse.api.dto.OutboundRequest;
import tech.migueldev.coffeewarehouse.api.dto.StoragePositionUpdateRequest;
import tech.migueldev.coffeewarehouse.api.dto.TransferRequest;
import tech.migueldev.coffeewarehouse.domain.Lot;
import tech.migueldev.coffeewarehouse.domain.Producer;
import tech.migueldev.coffeewarehouse.domain.StoragePosition;
import tech.migueldev.coffeewarehouse.domain.Warehouse;
import tech.migueldev.coffeewarehouse.repository.LotRepository;
import tech.migueldev.coffeewarehouse.repository.ProducerRepository;
import tech.migueldev.coffeewarehouse.repository.StoragePositionRepository;
import tech.migueldev.coffeewarehouse.repository.WarehouseRepository;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@AutoConfigureMockMvc
class StockMovementControllerTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProducerRepository producerRepository;

    @Autowired
    private WarehouseRepository warehouseRepository;

    @Autowired
    private StoragePositionRepository positionRepository;

    @Autowired
    private LotRepository lotRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Lot lot;
    private StoragePosition positionA;
    private StoragePosition positionB;
    private StoragePosition smallPosition;

    @BeforeEach
    void seed() {
        Producer producer = producerRepository.save(
                new Producer("COP-001", "Cooperativa Serra Alta", "Guaxupe", "MG"));
        Warehouse warehouse = warehouseRepository.save(
                new Warehouse("WH1", "Armazem Central", "Guaxupe", "MG"));

        positionA = positionRepository.save(
                new StoragePosition(warehouse, "01", "01", "01", new BigDecimal("60000.000")));
        positionB = positionRepository.save(
                new StoragePosition(warehouse, "02", "01", "01", new BigDecimal("60000.000")));
        smallPosition = positionRepository.save(
                new StoragePosition(warehouse, "03", "01", "01", new BigDecimal("1000.000")));

        lot = lotRepository.save(new Lot("LOT-001", producer, 2025,
                new BigDecimal("18000.000"), LocalDate.of(2025, 6, 10)));
    }

    private void inbound(StoragePosition target, String weight) throws Exception {
        var request = new InboundRequest(lot.getId(), target.getId(), new BigDecimal(weight), null, "receiving");
        mockMvc.perform(post("/api/movements/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("an inbound puts weight in a position and moves the lot to STORED")
    void inboundStoresTheLot() throws Exception {
        var request = new InboundRequest(lot.getId(), positionA.getId(),
                new BigDecimal("12000.000"), null, "receiving");

        mockMvc.perform(post("/api/movements/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", matchesPattern(".*/api/movements/[0-9]+")))
                .andExpect(jsonPath("$.type").value("INBOUND"))
                .andExpect(jsonPath("$.sourcePositionCode").doesNotExist())
                .andExpect(jsonPath("$.targetPositionCode").value("WH1-A01-B01-L01"));

        mockMvc.perform(get("/api/lots/{id}", lot.getId()))
                .andExpect(jsonPath("$.status").value("STORED"));

        mockMvc.perform(get("/api/storage-positions/{id}/occupancy", positionA.getId()))
                .andExpect(jsonPath("$.occupiedKg").value(12000.000))
                .andExpect(jsonPath("$.availableKg").value(48000.000));
    }

    @Test
    @DisplayName("an inbound beyond the capacity of the position is refused with 409")
    void refusesInboundBeyondCapacity() throws Exception {
        var request = new InboundRequest(lot.getId(), smallPosition.getId(),
                new BigDecimal("1000.001"), null, null);

        mockMvc.perform(post("/api/movements/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:capacity-exceeded"));

        mockMvc.perform(get("/api/storage-positions/{id}/occupancy", smallPosition.getId()))
                .andExpect(jsonPath("$.occupiedKg").value(0));
    }

    @Test
    @DisplayName("an inactive position refuses to receive stock")
    void refusesInboundIntoInactivePosition() throws Exception {
        mockMvc.perform(patch("/api/storage-positions/{id}/deactivate", positionA.getId()))
                .andExpect(status().isOk());

        var request = new InboundRequest(lot.getId(), positionA.getId(),
                new BigDecimal("100.000"), null, null);

        mockMvc.perform(post("/api/movements/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:position-not-available"));
    }

    @Test
    @DisplayName("a transfer moves weight between positions and leaves the lot balance unchanged")
    void transferMovesWeightWithoutChangingLotBalance() throws Exception {
        inbound(positionA, "12000.000");

        var request = new TransferRequest(lot.getId(), positionA.getId(), positionB.getId(),
                new BigDecimal("5000.000"), null, "consolidation");

        mockMvc.perform(post("/api/movements/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("TRANSFER"));

        mockMvc.perform(get("/api/storage-positions/{id}/occupancy", positionA.getId()))
                .andExpect(jsonPath("$.occupiedKg").value(7000.000));
        mockMvc.perform(get("/api/storage-positions/{id}/occupancy", positionB.getId()))
                .andExpect(jsonPath("$.occupiedKg").value(5000.000));

        mockMvc.perform(get("/api/lots/{id}/statement", lot.getId()))
                .andExpect(jsonPath("$.storedWeightKg").value(12000.000));
    }

    @Test
    @DisplayName("a transfer without balance at the source is refused with 409")
    void refusesTransferWithoutBalance() throws Exception {
        inbound(positionA, "1000.000");

        var request = new TransferRequest(lot.getId(), positionA.getId(), positionB.getId(),
                new BigDecimal("1500.000"), null, null);

        mockMvc.perform(post("/api/movements/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:insufficient-balance"));
    }

    @Test
    @DisplayName("a transfer to the same position is refused with 400")
    void refusesTransferToSamePosition() throws Exception {
        inbound(positionA, "1000.000");

        var request = new TransferRequest(lot.getId(), positionA.getId(), positionA.getId(),
                new BigDecimal("100.000"), null, null);

        mockMvc.perform(post("/api/movements/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem-type:invalid-movement"));
    }

    @Test
    @DisplayName("an outbound takes weight out and leaves the lot status alone")
    void outboundLeavesStatusAlone() throws Exception {
        inbound(positionA, "12000.000");

        var request = new OutboundRequest(lot.getId(), positionA.getId(),
                new BigDecimal("12000.000"), null, "shipping");

        mockMvc.perform(post("/api/movements/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.type").value("OUTBOUND"))
                .andExpect(jsonPath("$.targetPositionCode").doesNotExist());

        mockMvc.perform(get("/api/lots/{id}/statement", lot.getId()))
                .andExpect(jsonPath("$.storedWeightKg").value(0))
                .andExpect(jsonPath("$.status").value("STORED"));
    }

    @Test
    @DisplayName("an outbound beyond the balance is refused with 409")
    void refusesOutboundBeyondBalance() throws Exception {
        inbound(positionA, "1000.000");

        var request = new OutboundRequest(lot.getId(), positionA.getId(),
                new BigDecimal("1000.001"), null, null);

        mockMvc.perform(post("/api/movements/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:insufficient-balance"));
    }

    @Test
    @DisplayName("the statement lists every movement in order with the balance they produce")
    void statementListsTheHistory() throws Exception {
        inbound(positionA, "12000.000");

        var transfer = new TransferRequest(lot.getId(), positionA.getId(), positionB.getId(),
                new BigDecimal("5000.000"), null, null);
        mockMvc.perform(post("/api/movements/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transfer)))
                .andExpect(status().isCreated());

        var outbound = new OutboundRequest(lot.getId(), positionB.getId(),
                new BigDecimal("2000.000"), null, null);
        mockMvc.perform(post("/api/movements/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(outbound)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/lots/{id}/statement", lot.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lotCode").value("LOT-001"))
                .andExpect(jsonPath("$.netWeightKg").value(18000.000))
                .andExpect(jsonPath("$.storedWeightKg").value(10000.000))
                .andExpect(jsonPath("$.entries", hasSize(3)))
                .andExpect(jsonPath("$.entries[0].type").value("INBOUND"))
                .andExpect(jsonPath("$.entries[1].type").value("TRANSFER"))
                .andExpect(jsonPath("$.entries[2].type").value("OUTBOUND"));
    }

    @Test
    @DisplayName("an inbound beyond the net weight of the lot is refused with 409")
    void refusesInboundBeyondNetWeight() throws Exception {
        var request = new InboundRequest(lot.getId(), positionA.getId(),
                new BigDecimal("18000.001"), null, null);

        mockMvc.perform(post("/api/movements/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:lot-weight-exceeded"));

        mockMvc.perform(get("/api/lots/{id}/statement", lot.getId()))
                .andExpect(jsonPath("$.entries", hasSize(0)));
    }

    @Test
    @DisplayName("a lot put away across several positions is accepted up to exactly its net weight")
    void allowsSplittingALotUpToItsNetWeight() throws Exception {
        inbound(positionA, "12000.000");
        inbound(positionB, "6000.000");

        mockMvc.perform(get("/api/lots/{id}/statement", lot.getId()))
                .andExpect(jsonPath("$.storedWeightKg").value(18000.000));

        // The lot is now fully received: not one more gram of it exists.
        var request = new InboundRequest(lot.getId(), positionA.getId(),
                new BigDecimal("0.001"), null, null);
        mockMvc.perform(post("/api/movements/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:lot-weight-exceeded"));
    }

    /**
     * The ceiling counts everything ever received, not the current balance.
     * Shipping a lot out empties it without making it receivable again -- that
     * coffee left the warehouse and does not exist to arrive a second time.
     */
    @Test
    @DisplayName("a lot shipped out entirely still cannot be received again")
    void aFullyDispatchedLotCannotBeReceivedAgain() throws Exception {
        inbound(positionA, "18000.000");

        var outbound = new OutboundRequest(lot.getId(), positionA.getId(),
                new BigDecimal("18000.000"), null, null);
        mockMvc.perform(post("/api/movements/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(outbound)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/lots/{id}/statement", lot.getId()))
                .andExpect(jsonPath("$.storedWeightKg").value(0));

        var request = new InboundRequest(lot.getId(), positionA.getId(),
                new BigDecimal("100.000"), null, null);
        mockMvc.perform(post("/api/movements/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:lot-weight-exceeded"));
    }

    /**
     * The other direction of the capacity invariant. Nothing stops an operator
     * from re-rating a position, so the rule has to hold when the bar is
     * lowered towards the weight, not only when weight is raised towards the bar.
     */
    @Test
    @DisplayName("a capacity re-rated below what the position already holds is refused with 409")
    void refusesCapacityBelowCurrentOccupancy() throws Exception {
        inbound(positionA, "12000.000");

        var request = new StoragePositionUpdateRequest(new BigDecimal("11999.999"));

        mockMvc.perform(put("/api/storage-positions/{id}", positionA.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:capacity-exceeded"));

        mockMvc.perform(get("/api/storage-positions/{id}/occupancy", positionA.getId()))
                .andExpect(jsonPath("$.capacityKg").value(60000.000))
                .andExpect(jsonPath("$.availableKg").value(48000.000));
    }

    @Test
    @DisplayName("a capacity re-rated down to exactly what is stored is accepted")
    void acceptsCapacityMatchingCurrentOccupancy() throws Exception {
        inbound(positionA, "12000.000");

        var request = new StoragePositionUpdateRequest(new BigDecimal("12000.000"));

        mockMvc.perform(put("/api/storage-positions/{id}", positionA.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.capacityKg").value(12000.000));

        mockMvc.perform(get("/api/storage-positions/{id}/occupancy", positionA.getId()))
                .andExpect(jsonPath("$.availableKg").value(0));
    }

    /**
     * The status is set with direct SQL because nothing in the API can produce
     * it yet: SHIPPED is written by the shipment, in Phase 4. The rule it guards
     * exists now, though, so it is tested now rather than on trust.
     */
    @Test
    @DisplayName("a SHIPPED lot accepts no movement")
    void refusesAnyMovementOnAShippedLot() throws Exception {
        inbound(positionA, "12000.000");
        jdbcTemplate.update("UPDATE lot SET status = 'SHIPPED' WHERE id = ?", lot.getId());

        var request = new InboundRequest(lot.getId(), positionB.getId(),
                new BigDecimal("100.000"), null, null);
        mockMvc.perform(post("/api/movements/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:lot-not-movable"));

        var transfer = new TransferRequest(lot.getId(), positionA.getId(), positionB.getId(),
                new BigDecimal("100.000"), null, null);
        mockMvc.perform(post("/api/movements/transfers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(transfer)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:lot-not-movable"));

        var outbound = new OutboundRequest(lot.getId(), positionA.getId(),
                new BigDecimal("100.000"), null, null);
        mockMvc.perform(post("/api/movements/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(outbound)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:lot-not-movable"));

        // The ledger is untouched: the only entry is the one from before.
        mockMvc.perform(get("/api/lots/{id}/statement", lot.getId()))
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.storedWeightKg").value(12000.000));
    }

    /**
     * The reason backdating is bounded. Every balance check in the service reads
     * "how much is there now", which is only the right question if now is also
     * when the movement happened.
     */
    @Test
    @DisplayName("a movement dated behind the last one for the same lot and position is refused")
    void refusesAMovementRecordedOutOfOrder() throws Exception {
        inbound(positionA, "12000.000");

        var request = new OutboundRequest(lot.getId(), positionA.getId(),
                new BigDecimal("1000.000"),
                OffsetDateTime.now().minusDays(7), null);

        mockMvc.perform(post("/api/movements/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:out-of-order-movement"));

        // Had it been accepted, the statement would read: 1,000 kg out, then
        // 12,000 kg in -- a running balance that dips to minus 1,000 and
        // recovers, describing stock that was never actually negative.
        mockMvc.perform(get("/api/lots/{id}/statement", lot.getId()))
                .andExpect(jsonPath("$.entries", hasSize(1)))
                .andExpect(jsonPath("$.entries[0].type").value("INBOUND"));
    }

    @Test
    @DisplayName("backdating is allowed as long as it stays behind nothing already recorded")
    void allowsBackdatingThatDoesNotReorderHistory() throws Exception {
        OffsetDateTime received = OffsetDateTime.now().minusDays(10);
        var arrival = new InboundRequest(lot.getId(), positionA.getId(),
                new BigDecimal("12000.000"), received, "backdated receiving");
        mockMvc.perform(post("/api/movements/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(arrival)))
                .andExpect(status().isCreated());

        // Three days after the arrival, and still in the past: nothing is
        // reordered, so the ledger takes it.
        var dispatch = new OutboundRequest(lot.getId(), positionA.getId(),
                new BigDecimal("2000.000"), received.plusDays(3), null);
        mockMvc.perform(post("/api/movements/outbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dispatch)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/lots/{id}/statement", lot.getId()))
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.storedWeightKg").value(10000.000));
    }

    @Test
    @DisplayName("the ordering rule is per position, not across the whole lot")
    void ordersIndependentlyAtEachPosition() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();

        var intoA = new InboundRequest(lot.getId(), positionA.getId(),
                new BigDecimal("6000.000"), now.minusDays(1), null);
        mockMvc.perform(post("/api/movements/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(intoA)))
                .andExpect(status().isCreated());

        // Earlier than the movement into A, but position B has no history of
        // this lot at all, so there is nothing here to reorder.
        var intoB = new InboundRequest(lot.getId(), positionB.getId(),
                new BigDecimal("6000.000"), now.minusDays(5), null);
        mockMvc.perform(post("/api/movements/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(intoB)))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/lots/{id}/statement", lot.getId()))
                .andExpect(jsonPath("$.storedWeightKg").value(12000.000));
    }

    @Test
    @DisplayName("returns 404 when the position of a movement does not exist")
    void returnsNotFoundForUnknownPosition() throws Exception {
        var request = new InboundRequest(lot.getId(), 999_999L, new BigDecimal("100.000"), null, null);

        mockMvc.perform(post("/api/movements/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Storage position 999999 not found"));
    }

    @Test
    @DisplayName("rejects a movement with a non-positive weight with 400")
    void rejectsNonPositiveWeight() throws Exception {
        var request = new InboundRequest(lot.getId(), positionA.getId(), new BigDecimal("0.000"), null, null);

        mockMvc.perform(post("/api/movements/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("weightKg"));
    }
}
