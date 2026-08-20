package com.spin.transactions.service.provider;

import com.spin.transactions.model.Transaction;

/**
 * Port to the external transaction provider.
 *
 * Speaks in domain language on purpose: it takes the {@code Transaction} already
 * persisted in PENDING and returns the adapter-shaped {@link ProviderExecution}.
 * All infrastructure failures are translated to the exceptions declared below,
 * so the service never sees Spring Web, resilience libraries or checked I/O.
 *
 * Contract:
 * <ul>
 *   <li>Returns {@link ProviderExecution} only when the provider explicitly approved.</li>
 *   <li>Throws {@link ProviderRejectedException} when the provider explicitly rejected
 *       (known outcome; the transaction did not happen).</li>
 *   <li>Throws {@link ProviderUnavailableException} when the request never reached the
 *       provider or the provider is signalling it cannot handle the load (safe to retry).</li>
 *   <li>Throws {@link ProviderUnknownStateException} when the outcome is ambiguous
 *       (read timeout, unreadable 200): the charge may or may not have happened.
 *       The service must mark FAILED for reconciliation, never retry.</li>
 * </ul>
 */
public interface ProviderClient {

    ProviderExecution execute(Transaction transaction);
}
