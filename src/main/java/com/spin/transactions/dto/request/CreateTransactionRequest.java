package com.spin.transactions.dto.request;

import com.spin.transactions.model.TransactionCommand;
import com.spin.transactions.model.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.Locale;

/**
 * Public request contract for {@code POST /transactions}. Validation is done
 * with Jakarta Bean Validation; any failure surfaces as a {@code 400} through
 * {@link com.spin.transactions.exception.GlobalExceptionHandler}.
 *
 * <p>The record is <b>separate</b> from the domain {@code TransactionCommand}
 * on purpose: the wire contract and the internal command should be free to
 * evolve independently. The mapping happens in {@link #toCommand(String)},
 * which also normalises {@code currency} to upper-case at the boundary — the
 * DB {@code CHECK} constraint only accepts {@code 'MXN'}, so accepting
 * lower-case at the HTTP layer would blow up during INSERT.
 */
public record CreateTransactionRequest(
        @NotBlank String accountId,
        @NotNull TransactionType type,
        @NotNull @Positive BigDecimal amount,
        @NotBlank @Size(min = 3, max = 3) String currency,
        String description
) {

    public TransactionCommand toCommand(String idempotencyKey) {
        // Currency is normalized at the boundary. SupportedCurrencyRule compares with
        // equalsIgnoreCase and would happily accept "mxn", but the DB CHECK constraint
        // on the column enforces the uppercase literal 'MXN' and rejects the INSERT
        // — by then the transaction is already in flight and the failure surfaces as
        // an opaque 500. Normalizing here means no downstream layer has to remember.
        // Locale.ROOT avoids the Turkish-locale dotted-i trap on toUpperCase().
        return new TransactionCommand(
                accountId,
                type,
                amount,
                currency.toUpperCase(Locale.ROOT),
                description,
                idempotencyKey);
    }
}
