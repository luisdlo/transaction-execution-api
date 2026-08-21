package com.spin.transactions.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.List;
import java.util.Map;

/**
 * The one place that maps exceptions to HTTP status codes. Every error response is
 * a {@link ProblemDetail} (RFC 7807), so clients get a consistent shape.
 *
 * <p><b>422 vs 400.</b> 400 means the request is structurally malformed — missing
 * field, wrong type, out-of-range query parameter — and the caller has to change
 * the code that built the request. 422 means the request is structurally valid but
 * violates a business rule (e.g. amount below the account minimum) — the caller
 * has to change the input, not the code. The distinction matters to clients
 * because the fix lives in different places.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BusinessRuleViolationException.class)
    public ProblemDetail handleBusinessRuleViolation(BusinessRuleViolationException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
        pd.setTitle("Business rule violation");
        pd.setProperty("code", e.code());
        return pd;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException e) {
        List<Map<String, String>> errors = e.getBindingResult().getFieldErrors().stream()
                .map(fe -> Map.of(
                        "field", fe.getField(),
                        "message", fe.getDefaultMessage() == null ? "invalid" : fe.getDefaultMessage()))
                .toList();
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Request validation failed");
        pd.setTitle("Invalid request");
        pd.setProperty("errors", errors);
        return pd;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
        pd.setTitle("Invalid request");
        return pd;
    }

    // Malformed JSON or an unknown enum value inside the body (e.g. "type":"INVALID")
    // arrives here. Without this handler it would fall into the generic Exception
    // catch below and become a 500 — but the fault is on the caller's side.
    // The wrapped Jackson message leaks class paths ("Cannot deserialize value of
    // type com.spin.transactions.model.TransactionType"), so we substitute a stable
    // message and rely on the client fixing their payload.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ProblemDetail handleUnreadableBody(HttpMessageNotReadableException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "Request body is malformed or contains an invalid value");
        pd.setTitle("Invalid request");
        return pd;
    }

    // ?page=abc or ?status=FOO — a query parameter can't be converted to its target
    // type. Same reasoning as above: caller-side fault, must map to 400.
    // getRequiredType() exposes only the domain enum's simple name, which is
    // useful for the client and not sensitive.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        String requiredType = e.getRequiredType() == null ? "expected type" : e.getRequiredType().getSimpleName();
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST,
                "Parameter '" + e.getName() + "' could not be converted to " + requiredType);
        pd.setTitle("Invalid request");
        return pd;
    }

    @ExceptionHandler(ConcurrentTransactionUpdateException.class)
    public ProblemDetail handleConcurrentUpdate(ConcurrentTransactionUpdateException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        pd.setTitle("Concurrent update");
        return pd;
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ProblemDetail handleNotFound(TransactionNotFoundException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        pd.setTitle("Transaction not found");
        return pd;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleAnyOther(Exception e) {
        // Log the stack trace here but NEVER echo it to the client — leaking internal
        // messages (SQLState, stack lines, framework class names) is a classic
        // information-disclosure vector.
        log.error("Unhandled exception while processing request", e);
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
        pd.setTitle("Internal error");
        return pd;
    }
}
