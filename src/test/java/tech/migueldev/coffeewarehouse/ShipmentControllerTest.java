package tech.migueldev.coffeewarehouse;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import tech.migueldev.coffeewarehouse.api.dto.InboundRequest;
import tech.migueldev.coffeewarehouse.api.dto.LotUpdateRequest;
import tech.migueldev.coffeewarehouse.api.dto.ShipmentItemRequest;
import tech.migueldev.coffeewarehouse.api.dto.ShipmentItemWeightRequest;
import tech.migueldev.coffeewarehouse.api.dto.ShipmentRequest;
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
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * The shipment lifecycle, end to end over a real Postgres.
 *
 * The seed puts 12,000 kg of the 2024 lot in position A and 6,000 kg of the
 * 2025 lot in position B, which is the same 12/6 split the blend unit test uses
 * -- so the weighted average that comes back through HTTP can be checked
 * against the number worked out by hand there.
 */
@AutoConfigureMockMvc
class ShipmentControllerTest extends AbstractIntegrationTest {

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

    private Lot older;
    private Lot newer;
    private StoragePosition positionA;
    private StoragePosition positionB;

    @BeforeEach
    void seed() throws Exception {
        Producer producer = producerRepository.save(
                new Producer("COP-001", "Cooperativa Serra Alta", "Guaxupe", "MG"));
        Warehouse warehouse = warehouseRepository.save(
                new Warehouse("WH1", "Armazem Central", "Guaxupe", "MG"));

        positionA = positionRepository.save(
                new StoragePosition(warehouse, "01", "01", "01", new BigDecimal("60000.000")));
        positionB = positionRepository.save(
                new StoragePosition(warehouse, "02", "01", "01", new BigDecimal("60000.000")));

        older = new Lot("LOT-2024", producer, 2024,
                new BigDecimal("12000.000"), LocalDate.of(2024, 6, 10));
        older.updateClassification(200, new BigDecimal("11.50"), "17/18", "T6", "HARD");
        older = lotRepository.save(older);

        newer = new Lot("LOT-2025", producer, 2025,
                new BigDecimal("6000.000"), LocalDate.of(2025, 6, 10));
        newer.updateClassification(100, new BigDecimal("12.80"), "15/16", "T4/5", "SOFT");
        newer = lotRepository.save(newer);

        inbound(older, positionA, "12000.000");
        inbound(newer, positionB, "6000.000");
    }

    private void inbound(Lot lot, StoragePosition target, String weight) throws Exception {
        var request = new InboundRequest(lot.getId(), target.getId(),
                new BigDecimal(weight), null, "receiving");
        mockMvc.perform(post("/api/movements/inbound")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    private Long createShipment(String code) throws Exception {
        var request = new ShipmentRequest(code, "Port of Santos", LocalDate.of(2030, 7, 1));
        String body = mockMvc.perform(post("/api/shipments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private Long addItem(Long shipmentId, Lot lot, StoragePosition position, String weight)
            throws Exception {
        var request = new ShipmentItemRequest(lot.getId(), position.getId(), new BigDecimal(weight));
        String body = mockMvc.perform(post("/api/shipments/{id}/items", shipmentId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private void expectLotStatus(Lot lot, String expected) throws Exception {
        mockMvc.perform(get("/api/lots/{id}", lot.getId()))
                .andExpect(jsonPath("$.status").value(expected));
    }

    // -----------------------------------------------------------------
    // Composition and blend
    // -----------------------------------------------------------------

    @Test
    @DisplayName("composes a shipment and blends its moisture weighted by weight")
    void composesAndBlends() throws Exception {
        Long shipment = createShipment("SHP-001");
        addItem(shipment, older, positionA, "12000.000");
        addItem(shipment, newer, positionB, "6000.000");

        mockMvc.perform(get("/api/shipments/{id}", shipment))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.blend.totalWeightKg").value(18000.000))
                // (12000 x 11.50 + 6000 x 12.80) / 18000 = 11.93, not the 12.15
                // a simple average would give.
                .andExpect(jsonPath("$.blend.moisturePercent").value(11.93))
                .andExpect(jsonPath("$.blend.moistureBasisKg").value(18000.000))
                .andExpect(jsonPath("$.blend.screenSizes", hasSize(2)))
                .andExpect(jsonPath("$.blend.screenSizes[0].value").value("17/18"))
                .andExpect(jsonPath("$.blend.screenSizes[0].sharePercent").value(66.67));
    }

    @Test
    @DisplayName("adding to a line that already exists raises its weight instead of splitting it")
    void mergesRepeatedLines() throws Exception {
        Long shipment = createShipment("SHP-001");
        addItem(shipment, older, positionA, "4000.000");
        addItem(shipment, older, positionA, "3000.000");

        mockMvc.perform(get("/api/shipments/{id}", shipment))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].weightKg").value(7000.000))
                .andExpect(jsonPath("$.blend.totalWeightKg").value(7000.000));
    }

    // -----------------------------------------------------------------
    // Reservation
    // -----------------------------------------------------------------

    @Test
    @DisplayName("putting a lot on a draft reserves it without moving anything")
    void reservesWithoutMoving() throws Exception {
        Long shipment = createShipment("SHP-001");
        addItem(shipment, older, positionA, "12000.000");

        expectLotStatus(older, "RESERVED");

        // Nothing moved: the ledger and the position are exactly as before.
        mockMvc.perform(get("/api/lots/{id}/statement", older.getId()))
                .andExpect(jsonPath("$.storedWeightKg").value(12000.000))
                .andExpect(jsonPath("$.entries", hasSize(1)));
        mockMvc.perform(get("/api/storage-positions/{id}/occupancy", positionA.getId()))
                .andExpect(jsonPath("$.occupiedKg").value(12000.000));
    }

    /**
     * The reason reserved weight is derived rather than flagged. Both shipments
     * see a ledger balance of 12,000 kg, and the balance alone would let each of
     * them take all of it.
     */
    @Test
    @DisplayName("a second draft cannot claim weight the first one already holds")
    void refusesToClaimReservedWeightTwice() throws Exception {
        Long first = createShipment("SHP-001");
        addItem(first, older, positionA, "10000.000");

        Long second = createShipment("SHP-002");
        var request = new ShipmentItemRequest(older.getId(), positionA.getId(),
                new BigDecimal("5000.000"));

        mockMvc.perform(post("/api/shipments/{id}/items", second)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:insufficient-availability"));

        // The 2,000 kg the first shipment left behind is still claimable.
        addItem(second, older, positionA, "2000.000");
    }

    @Test
    @DisplayName("raising a line only has to fit the weight it does not already hold")
    void changingAWeightDoesNotChargeTheShipmentTwice() throws Exception {
        Long shipment = createShipment("SHP-001");
        Long item = addItem(shipment, older, positionA, "10000.000");

        // 12,000 is more than the 2,000 still free, but the line already holds
        // 10,000 of it -- counting its own claim against it would refuse this.
        mockMvc.perform(put("/api/shipments/{id}/items/{itemId}", shipment, item)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new ShipmentItemWeightRequest(new BigDecimal("12000.000")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].weightKg").value(12000.000));
    }

    @Test
    @DisplayName("removing the last line lets the lot out of RESERVED")
    void removingTheLastLineReleasesTheLot() throws Exception {
        Long shipment = createShipment("SHP-001");
        Long item = addItem(shipment, older, positionA, "5000.000");
        expectLotStatus(older, "RESERVED");

        mockMvc.perform(delete("/api/shipments/{id}/items/{itemId}", shipment, item))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        expectLotStatus(older, "STORED");
    }

    /**
     * The case a toggled flag would get wrong: one draft lets the lot go while
     * another still holds it.
     */
    @Test
    @DisplayName("a lot on two drafts stays RESERVED when only one of them releases it")
    void staysReservedWhileAnotherDraftHoldsIt() throws Exception {
        Long first = createShipment("SHP-001");
        Long firstItem = addItem(first, older, positionA, "4000.000");
        Long second = createShipment("SHP-002");
        addItem(second, older, positionA, "4000.000");

        mockMvc.perform(delete("/api/shipments/{id}/items/{itemId}", first, firstItem))
                .andExpect(status().isOk());

        expectLotStatus(older, "RESERVED");
    }

    @Test
    @DisplayName("cancelling a draft releases everything it was holding")
    void cancellingReleasesTheReservation() throws Exception {
        Long shipment = createShipment("SHP-001");
        addItem(shipment, older, positionA, "12000.000");

        mockMvc.perform(patch("/api/shipments/{id}/cancel", shipment))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));

        expectLotStatus(older, "STORED");

        // And the weight is claimable again.
        Long other = createShipment("SHP-002");
        addItem(other, older, positionA, "12000.000");
    }

    // -----------------------------------------------------------------
    // Confirmation
    // -----------------------------------------------------------------

    @Test
    @DisplayName("confirming writes an outbound per line and empties the positions")
    void confirmationWritesTheLedger() throws Exception {
        Long shipment = createShipment("SHP-001");
        addItem(shipment, older, positionA, "12000.000");
        addItem(shipment, newer, positionB, "6000.000");

        mockMvc.perform(patch("/api/shipments/{id}/confirm", shipment))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmedAt").isNotEmpty());

        mockMvc.perform(get("/api/lots/{id}/statement", older.getId()))
                .andExpect(jsonPath("$.storedWeightKg").value(0))
                .andExpect(jsonPath("$.entries", hasSize(2)))
                .andExpect(jsonPath("$.entries[1].type").value("OUTBOUND"));
        mockMvc.perform(get("/api/storage-positions/{id}/occupancy", positionA.getId()))
                .andExpect(jsonPath("$.occupiedKg").value(0));

        expectLotStatus(older, "SHIPPED");
        expectLotStatus(newer, "SHIPPED");
    }

    /**
     * The trap. SHIPPED is terminal and refuses every later movement, so a lot
     * that only partly went out must not be marked with it -- the remainder
     * would be stranded in the warehouse permanently.
     */
    @Test
    @DisplayName("a partially dispatched lot goes back to STORED, never to SHIPPED")
    void partialDispatchDoesNotStrandTheRemainder() throws Exception {
        Long shipment = createShipment("SHP-001");
        addItem(shipment, older, positionA, "5000.000");

        mockMvc.perform(patch("/api/shipments/{id}/confirm", shipment))
                .andExpect(status().isOk());

        expectLotStatus(older, "STORED");
        mockMvc.perform(get("/api/lots/{id}/statement", older.getId()))
                .andExpect(jsonPath("$.storedWeightKg").value(7000.000));

        // The remainder is genuinely still usable: it can move, and it can be
        // shipped again. That is what SHIPPED would have taken away.
        Long another = createShipment("SHP-002");
        addItem(another, older, positionA, "7000.000");
        mockMvc.perform(patch("/api/shipments/{id}/confirm", another))
                .andExpect(status().isOk());

        expectLotStatus(older, "SHIPPED");
    }

    /**
     * A confirmed shipment reports the blend it went out with, not the blend its
     * lots would produce today. Grading is revisable; what was dispatched is not.
     */
    @Test
    @DisplayName("the blend is frozen at confirmation and survives a later re-grade")
    void blendIsFrozenAtConfirmation() throws Exception {
        Long shipment = createShipment("SHP-001");
        addItem(shipment, older, positionA, "12000.000");
        addItem(shipment, newer, positionB, "6000.000");

        mockMvc.perform(patch("/api/shipments/{id}/confirm", shipment))
                .andExpect(jsonPath("$.blend.moisturePercent").value(11.93));

        // Re-grade the older lot well away from where it was.
        mockMvc.perform(put("/api/lots/{id}", older.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LotUpdateRequest(
                                200, new BigDecimal("20.00"), "17/18", "T6", "HARD"))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/shipments/{id}/blend", shipment))
                .andExpect(jsonPath("$.moisturePercent").value(11.93))
                .andExpect(jsonPath("$.totalWeightKg").value(18000.000));
    }

    @Test
    @DisplayName("a confirmed shipment accepts no further changes")
    void confirmedShipmentIsClosed() throws Exception {
        Long shipment = createShipment("SHP-001");
        addItem(shipment, older, positionA, "12000.000");
        mockMvc.perform(patch("/api/shipments/{id}/confirm", shipment))
                .andExpect(status().isOk());

        var request = new ShipmentItemRequest(newer.getId(), positionB.getId(),
                new BigDecimal("1000.000"));
        mockMvc.perform(post("/api/shipments/{id}/items", shipment)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:shipment-not-editable"));

        mockMvc.perform(patch("/api/shipments/{id}/confirm", shipment))
                .andExpect(status().isConflict());
        mockMvc.perform(patch("/api/shipments/{id}/cancel", shipment))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("an empty shipment cannot be confirmed")
    void refusesToConfirmAnEmptyShipment() throws Exception {
        Long shipment = createShipment("SHP-001");

        mockMvc.perform(patch("/api/shipments/{id}/confirm", shipment))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:empty-shipment"));
    }

    @Test
    @DisplayName("a shipped lot cannot be put on another shipment")
    void refusesToComposeWithAShippedLot() throws Exception {
        Long shipment = createShipment("SHP-001");
        addItem(shipment, older, positionA, "12000.000");
        mockMvc.perform(patch("/api/shipments/{id}/confirm", shipment))
                .andExpect(status().isOk());

        Long another = createShipment("SHP-002");
        var request = new ShipmentItemRequest(older.getId(), positionA.getId(),
                new BigDecimal("100.000"));

        mockMvc.perform(post("/api/shipments/{id}/items", another)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("urn:problem-type:lot-not-movable"));
    }

    // -----------------------------------------------------------------
    // Picking suggestion
    // -----------------------------------------------------------------

    @Test
    @DisplayName("suggests the oldest crop first and stops once the weight is covered")
    void suggestsOldestCropFirst() throws Exception {
        mockMvc.perform(get("/api/shipments/picking-suggestion").param("weightKg", "15000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullyCovered").value(true))
                .andExpect(jsonPath("$.suggestedKg").value(15000))
                .andExpect(jsonPath("$.shortfallKg").value(0))
                .andExpect(jsonPath("$.lines", hasSize(2)))
                // 2024 before 2025, and the first line is taken in full.
                .andExpect(jsonPath("$.lines[0].lotCode").value("LOT-2024"))
                .andExpect(jsonPath("$.lines[0].cropYear").value(2024))
                .andExpect(jsonPath("$.lines[0].weightKg").value(12000.000))
                .andExpect(jsonPath("$.lines[0].sourcePositionCode").value("WH1-A01-B01-L01"))
                // Only the remaining 3,000 is taken from the newer crop, even
                // though 6,000 is there.
                .andExpect(jsonPath("$.lines[1].lotCode").value("LOT-2025"))
                .andExpect(jsonPath("$.lines[1].weightKg").value(3000.000))
                .andExpect(jsonPath("$.lines[1].availableKg").value(6000.000));
    }

    @Test
    @DisplayName("reports a shortfall rather than failing when the warehouse is short")
    void reportsAShortfall() throws Exception {
        mockMvc.perform(get("/api/shipments/picking-suggestion").param("weightKg", "25000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullyCovered").value(false))
                .andExpect(jsonPath("$.suggestedKg").value(18000.000))
                .andExpect(jsonPath("$.shortfallKg").value(7000.000))
                .andExpect(jsonPath("$.lines", hasSize(2)));
    }

    @Test
    @DisplayName("does not suggest weight another draft has already reserved")
    void skipsReservedWeight() throws Exception {
        Long shipment = createShipment("SHP-001");
        addItem(shipment, older, positionA, "12000.000");

        mockMvc.perform(get("/api/shipments/picking-suggestion").param("weightKg", "15000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fullyCovered").value(false))
                .andExpect(jsonPath("$.suggestedKg").value(6000.000))
                // The reserved lot is gone from the suggestion entirely.
                .andExpect(jsonPath("$.lines", hasSize(1)))
                .andExpect(jsonPath("$.lines[0].lotCode").value("LOT-2025"));
    }

    @Test
    @DisplayName("narrows the suggestion to one crop year when asked")
    void filtersByCropYear() throws Exception {
        mockMvc.perform(get("/api/shipments/picking-suggestion")
                        .param("weightKg", "15000")
                        .param("cropYear", "2025"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines", hasSize(1)))
                .andExpect(jsonPath("$.lines[0].lotCode").value("LOT-2025"))
                .andExpect(jsonPath("$.suggestedKg").value(6000.000));
    }

    @Test
    @DisplayName("suggests nothing once everything has shipped")
    void suggestsNothingWhenTheWarehouseIsEmpty() throws Exception {
        Long shipment = createShipment("SHP-001");
        addItem(shipment, older, positionA, "12000.000");
        addItem(shipment, newer, positionB, "6000.000");
        mockMvc.perform(patch("/api/shipments/{id}/confirm", shipment))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/shipments/picking-suggestion").param("weightKg", "1000"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines", hasSize(0)))
                .andExpect(jsonPath("$.shortfallKg").value(1000));
    }

    /**
     * The constraint here sits on a @RequestParam of a @Validated controller,
     * which fails with ConstraintViolationException rather than the
     * MethodArgumentNotValidException a request body raises. Without a handler
     * of its own it left as a 500 carrying the Java method name, so this asserts
     * the answer a client actually gets, not merely that the request is refused.
     */
    @Test
    @DisplayName("refuses a non-positive weight as a validation error, not a server error")
    void refusesANonPositiveWeight() throws Exception {
        mockMvc.perform(get("/api/shipments/picking-suggestion").param("weightKg", "-5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").value("urn:problem-type:validation-failed"))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                // The parameter as the API names it -- not "suggestPicking.weightKg".
                .andExpect(jsonPath("$.errors[0].field").value("weightKg"));
    }
}
