package tech.migueldev.coffeewarehouse.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import tech.migueldev.coffeewarehouse.api.dto.BlendResponse;
import tech.migueldev.coffeewarehouse.api.dto.PickingSuggestionResponse;
import tech.migueldev.coffeewarehouse.api.dto.ShipmentItemRequest;
import tech.migueldev.coffeewarehouse.api.dto.ShipmentItemResponse;
import tech.migueldev.coffeewarehouse.api.dto.ShipmentItemWeightRequest;
import tech.migueldev.coffeewarehouse.api.dto.ShipmentRequest;
import tech.migueldev.coffeewarehouse.api.dto.ShipmentResponse;
import tech.migueldev.coffeewarehouse.domain.Shipment;
import tech.migueldev.coffeewarehouse.domain.ShipmentItem;
import tech.migueldev.coffeewarehouse.domain.ShipmentStatus;
import tech.migueldev.coffeewarehouse.service.ShipmentService;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.util.UriComponentsBuilder;

import java.math.BigDecimal;
import java.net.URI;

/**
 * A shipment is built up, then confirmed or cancelled.
 *
 * Items live under the shipment because they have no identity without it: there
 * is no {@code /api/shipment-items}, and there should not be. Confirm and cancel
 * are PATCH transitions rather than a PUT of the status field, so the API says
 * what happens rather than letting a client assign any state it likes -- there
 * is no path back from either.
 */
@Tag(name = "Shipments", description = "Composing a dispatch, blending it, and sending it")
@RestController
@RequestMapping("/api/shipments")
@Validated
public class ShipmentController {

    private final ShipmentService service;

    public ShipmentController(ShipmentService service) {
        this.service = service;
    }

    @Operation(
            summary = "Open a shipment in DRAFT",
            description = """
                    A shipment starts empty. Its composition arrives afterwards, one item \
                    at a time, because each line has to be checked against the stock that \
                    is actually free at the moment it is added.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Created; `Location` names the shipment"),
            @ApiResponse(responseCode = "400", description = "`validation-failed`"),
            @ApiResponse(responseCode = "409", description = "`duplicate-code`")
    })
    @PostMapping
    public ResponseEntity<ShipmentResponse> create(@RequestBody @Valid ShipmentRequest request,
                                                   UriComponentsBuilder uriBuilder) {
        Shipment shipment = service.create(request);
        URI location = uriBuilder.path("/api/shipments/{id}")
                .buildAndExpand(shipment.getId()).toUri();
        return ResponseEntity.created(location).body(ShipmentResponse.from(shipment));
    }

    /**
     * What to pick, and from where, to make up a weight -- oldest crop first.
     *
     * A GET because it changes nothing: it reserves no stock and writes no row.
     * Mapped above {@code /{id}} so "picking-suggestion" is never read as a
     * shipment id.
     */
    @Operation(
            summary = "What to pick, and from where, oldest crop first",
            description = """
                    Walks the free stock in FIFO order by crop year, taking from each \
                    (lot, position) pair until the requested weight is covered. Coffee \
                    does not improve in storage, so shipping the oldest crop first is what \
                    keeps a warehouse from quietly accumulating a corner nobody will take.

                    **It only suggests.** Nothing is reserved, moved or written. A caller \
                    turns a line into a claim by posting it as a shipment item, which \
                    re-checks availability at that moment -- in between, someone else may \
                    have taken the same coffee. Reserving here instead would mean every \
                    browse of the warehouse locked stock away from everyone else.

                    Free means the ledger balance minus what other **draft** shipments \
                    have already claimed, so a suggestion never offers the same kilo twice.

                    A request larger than the warehouse holds comes back with \
                    `fullyCovered: false` and a `shortfallKg` rather than an error: \
                    "here is the 14 tonnes that exist, and where" is a useful answer to a \
                    request for 18.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The suggested lines, and any shortfall"),
            @ApiResponse(responseCode = "400",
                    description = "`validation-failed` -- `weightKg` must be greater than zero")
    })
    @GetMapping("/picking-suggestion")
    public PickingSuggestionResponse suggestPicking(
            @RequestParam @DecimalMin(value = "0.0", inclusive = false) BigDecimal weightKg,
            @RequestParam(required = false) Long producerId,
            @RequestParam(required = false) Integer cropYear) {

        return service.suggestPicking(weightKg, producerId, cropYear);
    }

    @Operation(summary = "Read one shipment, with its items and blend")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The shipment"),
            @ApiResponse(responseCode = "404", description = "`resource-not-found`")
    })
    @GetMapping("/{id}")
    public ShipmentResponse findById(@PathVariable Long id) {
        return ShipmentResponse.from(service.findById(id));
    }

    /**
     * Summaries only: a page of shipments does not fetch their compositions, so
     * the listing carries no items and no blend.
     */
    @Operation(
            summary = "List shipments",
            description = """
                    Summaries only: a page does not fetch each shipment's composition, so \
                    the listing carries no items and no blend. Read one by id for those.""")
    @ApiResponse(responseCode = "200", description = "A page of shipment summaries")
    @GetMapping
    public PagedModel<ShipmentResponse> search(
            @RequestParam(required = false) ShipmentStatus status,
            @PageableDefault(size = 20, sort = "code", direction = Sort.Direction.ASC) Pageable pageable) {

        return new PagedModel<>(service.search(status, pageable).map(ShipmentResponse::summary));
    }

    /**
     * The blend on its own, for a caller that wants the numbers without the
     * composition behind them.
     */
    @Operation(
            summary = "What the shipment adds up to",
            description = """
                    **Moisture is averaged weighted by weight**, which is the whole point \
                    of the calculation: 12,000 kg at 11.50% blended with 6,000 kg at \
                    12.80% is 11.93%, not the 12.15% a simple average gives. \
                    `moistureBasisKg` reports the weight that actually had a reading, so \
                    the average cannot quietly overstate its coverage -- lots with no \
                    moisture are excluded from both sides of the ratio rather than \
                    counted as zero.

                    **Classification is composed, never averaged.** There is no average \
                    of `HARD` and `SOFT`, so screen size, defect type and cup quality are \
                    each reported as the share of total weight behind every distinct \
                    value. For screen size that is not a workaround but the trade \
                    standard: a sieve analysis reports the mass percentage retained on \
                    each screen. These shares divide by the *total* weight, so a sum \
                    below 100% is visible as coffee nobody graded.

                    **Frozen once confirmed.** While `DRAFT` the numbers are computed from \
                    the items each time. At confirmation the weight, the moisture average \
                    and its basis are copied onto the row and never recomputed, because a \
                    lot's moisture is revisable and a confirmed shipment recomputed from \
                    today's data would report a blend that was never shipped. The \
                    categorical shares stay derived -- a re-grade is new information about \
                    the same coffee rather than a change to what was sent.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "The blend"),
            @ApiResponse(responseCode = "404", description = "`resource-not-found`")
    })
    @GetMapping("/{id}/blend")
    public BlendResponse blend(@PathVariable Long id) {
        return BlendResponse.from(service.findById(id).blend());
    }

    @Operation(
            summary = "Add a lot to the shipment, out of a named position",
            description = """
                    An item is a picking instruction, not just a quantity: it names the \
                    position the weight leaves from, because a lot can sit in several and \
                    the ledger has to be told which one. The suggestion endpoint proposes \
                    those triples; this one commits them.

                    Adding the same (lot, position) twice raises the existing line rather \
                    than creating a second one.

                    **Availability, not balance, is what has to fit.** Putting a lot on a \
                    draft shipment moves nothing, so its ledger balance is untouched -- \
                    which is exactly why the balance alone is the wrong number to check \
                    against. Two shipments would each see the full 10,000 kg and each \
                    claim it. What is checked is the balance at the position minus the \
                    weight other draft shipments have already committed.

                    The lot is marked `RESERVED`, which is a label meaning "some of this \
                    is spoken for" and never a quantity: how much is always summed from \
                    the draft items.""")
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Line added; `Location` names it"),
            @ApiResponse(responseCode = "400", description = "`validation-failed`"),
            @ApiResponse(responseCode = "404", description = "`resource-not-found`"),
            @ApiResponse(responseCode = "409", description = """
                    `insufficient-availability` (the stock exists but another draft \
                    already claimed it), `lot-not-movable` (the lot is SHIPPED), \
                    `shipment-not-editable` (the shipment is no longer DRAFT), or \
                    `concurrent-modification`""")
    })
    @PostMapping("/{id}/items")
    public ResponseEntity<ShipmentItemResponse> addItem(
            @PathVariable Long id,
            @RequestBody @Valid ShipmentItemRequest request,
            UriComponentsBuilder uriBuilder) {

        ShipmentItem item = service.addItem(id, request);
        URI location = uriBuilder.path("/api/shipments/{id}/items/{itemId}")
                .buildAndExpand(id, item.getId()).toUri();
        return ResponseEntity.created(location).body(ShipmentItemResponse.from(item));
    }

    @Operation(
            summary = "Change how much of a line is being shipped",
            description = """
                    The weight is the only revisable part of a line. Changing which lot or \
                    which position it refers to is a different instruction, so it is a \
                    different line -- remove this one and add that one.

                    The new total is checked against availability with this shipment's own \
                    current claim excluded, so raising a line is not charged twice for \
                    weight it is about to replace.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Line updated"),
            @ApiResponse(responseCode = "400", description = "`validation-failed`"),
            @ApiResponse(responseCode = "404", description = "`resource-not-found`"),
            @ApiResponse(responseCode = "409", description = """
                    `insufficient-availability`, `shipment-not-editable` or \
                    `concurrent-modification`""")
    })
    @PutMapping("/{id}/items/{itemId}")
    public ShipmentResponse changeItemWeight(@PathVariable Long id,
                                             @PathVariable Long itemId,
                                             @RequestBody @Valid ShipmentItemWeightRequest request) {
        return ShipmentResponse.from(service.changeItemWeight(id, itemId, request));
    }

    @Operation(
            summary = "Drop a line from the shipment",
            description = """
                    Releases the weight it was holding. The lot leaves `RESERVED` only if \
                    no other draft shipment still claims it -- the status is recomputed \
                    rather than toggled, because a lot on two drafts released by one is \
                    still spoken for by the other.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Line removed"),
            @ApiResponse(responseCode = "404", description = "`resource-not-found`"),
            @ApiResponse(responseCode = "409", description = "`shipment-not-editable`")
    })
    @DeleteMapping("/{id}/items/{itemId}")
    public ShipmentResponse removeItem(@PathVariable Long id, @PathVariable Long itemId) {
        return ShipmentResponse.from(service.removeItem(id, itemId));
    }

    @Operation(
            summary = "Send the shipment",
            description = """
                    Every line becomes an outbound movement, the blend is frozen onto the \
                    row, and the shipment becomes `CONFIRMED`, which is terminal.

                    The movements go through the same ledger operation a manual outbound \
                    uses. The shipment gets no private door into `stock_movement`, so \
                    every invariant still applies -- the position is locked the same way, \
                    the balance is checked the same way -- and the concurrency story stays \
                    a single story. The balance check is not redundant with the one done \
                    when the line was added: stock can have moved in between, and this is \
                    the moment the weight actually leaves.

                    **A partially dispatched lot does not become SHIPPED.** That status is \
                    terminal and refuses every later movement, so a lot that sent 5,000 of \
                    its 12,000 kg would have the remaining 7,000 stranded in the warehouse \
                    permanently. A lot goes `SHIPPED` only once its remaining balance is \
                    zero; otherwise it is ordinary stored stock again, or stays `RESERVED` \
                    if another draft still holds it.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Confirmed and dispatched"),
            @ApiResponse(responseCode = "404", description = "`resource-not-found`"),
            @ApiResponse(responseCode = "409", description = """
                    `empty-shipment` (nothing to send), `shipment-not-editable` (already \
                    confirmed or cancelled), `insufficient-balance` (the stock moved since \
                    the line was added), or `concurrent-modification`""")
    })
    @PatchMapping("/{id}/confirm")
    public ShipmentResponse confirm(@PathVariable Long id) {
        return ShipmentResponse.from(service.confirm(id));
    }

    @Operation(
            summary = "Abandon a draft shipment",
            description = """
                    Releases every reservation and closes the shipment as `CANCELLED`, \
                    which is terminal.

                    **A confirmed shipment is never cancelled.** Its movements are in the \
                    ledger, and the ledger is corrected by recording the opposite, never \
                    by deletion.""")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Cancelled"),
            @ApiResponse(responseCode = "404", description = "`resource-not-found`"),
            @ApiResponse(responseCode = "409",
                    description = "`shipment-not-editable` -- it is already confirmed or cancelled")
    })
    @PatchMapping("/{id}/cancel")
    public ShipmentResponse cancel(@PathVariable Long id) {
        return ShipmentResponse.from(service.cancel(id));
    }
}
