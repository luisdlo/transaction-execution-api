package com.spin.transactions.service.rule;

import com.spin.transactions.exception.BusinessRuleViolationException;
import com.spin.transactions.model.TransactionCommand;

/**
 * Contract for a single business rule.
 *
 * <p>Rules are evaluated by the service <b>before</b> anything is persisted or
 * sent to the provider. A rule signals rejection by throwing
 * {@link BusinessRuleViolationException} with a stable {@code code} that ends
 * up in the {@code 422} {@code ProblemDetail} response.
 *
 * <p>All implementations are discovered by Spring as {@code List<TransactionRule>}
 * and applied in the order Spring injects them. Adding a new rule is a matter
 * of adding a class annotated with {@code @Component}; the service does not
 * need to change (open/closed principle).
 *
 * <p>Design guardrails:
 * <ul>
 *   <li><b>One class per rule</b> — keeps failure codes and messages localised.</li>
 *   <li><b>No I/O</b> — a rule must be a pure function of {@link TransactionCommand}
 *       and its injected configuration. Anything that talks to the DB or the
 *       network belongs elsewhere.</li>
 *   <li><b>Fail-fast</b> — throw on the first violation; do not accumulate.
 *       The 422 response describes one problem at a time.</li>
 * </ul>
 */
@FunctionalInterface
public interface TransactionRule {

    /**
     * @throws BusinessRuleViolationException if the rule rejects the command.
     */
    void validate(TransactionCommand command);
}
