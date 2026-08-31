package com.spin.transactions.controller;

import com.spin.transactions.dto.request.CreateTransactionRequest;
import com.spin.transactions.dto.response.PagedResponse;
import com.spin.transactions.dto.response.TransactionResponse;
import com.spin.transactions.model.PagedResult;
import com.spin.transactions.model.Transaction;
import com.spin.transactions.model.TransactionFilter;
import com.spin.transactions.model.TransactionStatus;
import com.spin.transactions.model.TransactionType;
import com.spin.transactions.service.TransactionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;

/**
 * HTTP boundary for the transactions resource. This is an adapter — no
 * business logic and no {@code try/catch}: the {@link TransactionService}
 * makes decisions, and
 * {@link com.spin.transactions.exception.GlobalExceptionHandler} maps every
 * exception to a {@code ProblemDetail} (RFC 7807) response.
 *
 * <h2>HTTP status conventions</h2>
 *
 * <table border="1">
 *   <caption>HTTP status codes returned by this controller</caption>
 *   <tr><th>Endpoint</th><th>Code</th><th>Meaning</th></tr>
 *   <tr><td>{@code POST /transactions}</td><td>{@code 201}</td>
 *       <td>Transaction persisted (EXECUTED, REJECTED <b>or</b> FAILED).
 *           A provider rejection is a business outcome, <b>not</b> an HTTP
 *           error — the client must inspect {@code status} in the body.</td></tr>
 *   <tr><td>{@code POST /transactions}</td><td>{@code 400}</td>
 *       <td>Malformed body, invalid enum, {@code @Positive}/{@code @NotBlank}
 *           violation.</td></tr>
 *   <tr><td>{@code POST /transactions}</td><td>{@code 422}</td>
 *       <td>Structurally valid but violates a business rule
 *           (min amount, debit limit, unsupported currency).</td></tr>
 *   <tr><td>{@code POST /transactions}</td><td>{@code 409}</td>
 *       <td>Concurrent state transition on the same row.</td></tr>
 *   <tr><td>{@code GET /transactions}</td><td>{@code 200}</td>
 *       <td>Paged result. {@code hasNext} without a {@code COUNT(*)}.</td></tr>
 *   <tr><td>{@code GET /transactions}</td><td>{@code 400}</td>
 *       <td>Invalid query parameter (unknown enum, page/limit out of range).</td></tr>
 * </table>
 *
 * <h2>Idempotency</h2>
 * The optional {@code Idempotency-Key} request header is forwarded to the
 * service; a repeated POST with the same key and account returns the
 * pre-existing transaction (same {@code id}), never a new row.
 */
@RestController
@RequestMapping("/transactions")
@Tag(name = "Transactions", description = "Execute transactions against the external provider and read them back.")
public class TransactionController {

    private final TransactionService service;

    public TransactionController(TransactionService service) {
        this.service = service;
    }

    // 201 is returned on every terminal state — EXECUTED, REJECTED and FAILED.
    // A provider rejection is a business outcome, not an HTTP error: the transaction
    // WAS created and IS persisted, so the caller must inspect the `status` field in
    // the body, not the HTTP code. Reserving 4xx for HTTP-shaped failures keeps
    // idempotent retries and monitoring dashboards honest.
    @Operation(
            summary = "Execute a transaction",
            description = "Runs business rules, persists the transaction and calls the external provider. "
                    + "Returns 201 for every terminal outcome (EXECUTED, REJECTED, FAILED) — a provider "
                    + "rejection is not an HTTP error. Clients MUST read the `status` field to distinguish."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Transaction persisted; check `status` for outcome."),
            @ApiResponse(responseCode = "400", description = "Malformed request body or invalid field."),
            @ApiResponse(responseCode = "422", description = "Business rule violation (e.g. amount below minimum)."),
            @ApiResponse(responseCode = "409", description = "Concurrent state transition on the same transaction."),
            @ApiResponse(responseCode = "500", description = "Unexpected internal error.")
    })
    @PostMapping
    public ResponseEntity<TransactionResponse> create(
            @Valid @RequestBody CreateTransactionRequest request,
            @RequestHeader(value = ApiHeaders.IDEMPOTENCY_KEY, required = false) String idempotencyKey) {
        Transaction result = service.execute(request.toCommand(idempotencyKey));
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}")
                .buildAndExpand(result.id())
                .toUri();
        return ResponseEntity.created(location).body(TransactionResponse.from(result));
    }

    @Operation(
            summary = "List transactions",
            description = "Paginated read. hasNext is computed by requesting limit+1 rows internally, "
                    + "so no COUNT(*) is issued against the table."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Paged result."),
            @ApiResponse(responseCode = "400", description = "Invalid query parameter (unknown enum, page<0, limit out of range)."),
            @ApiResponse(responseCode = "500", description = "Unexpected internal error.")
    })
    @GetMapping
    public PagedResponse<TransactionResponse> find(
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(required = false) TransactionType type,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int limit) {
        TransactionFilter filter = new TransactionFilter(accountId, status, type);
        PagedResult<Transaction> result = service.find(filter, page, limit);
        List<TransactionResponse> items = result.items().stream()
                .map(TransactionResponse::from)
                .toList();
        return new PagedResponse<>(items, result.page(), result.limit(), result.hasNext());
    }
}
