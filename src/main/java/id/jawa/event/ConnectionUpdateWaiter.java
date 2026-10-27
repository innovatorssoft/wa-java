package id.jawa.event;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * Predicate-based wait on connection updates, mirroring Baileys'
 * {@code waitForConnectionUpdate} ({@code bindWaitForConnectionUpdate}
 * in {@code lib/Utils/generics.js}, bound to
 * {@code connection.update} in {@code lib/Socket/socket.js}).
 *
 * <p>Baileys' form is {@code (check, timeoutMs) => Promise<void>}:
 * it resolves (with no value) when {@code check(update)} is true,
 * and rejects on timeout ({@code promiseTimeout}) or when a
 * {@code close} update arrives. This port deliberately differs in
 * two task-specified ways, and only those two:
 * <ul>
 *   <li>Timeout (and, in effect, a close the predicate does not
 *       match) completes the future with {@code null} instead of
 *       failing it, so callers never block indefinitely and never
 *       need to catch a timeout exception to distinguish "no match"
 *       from "match". A {@code close} update is still delivered to
 *       the predicate like any other update, so a predicate matching
 *       {@code CLOSE} resolves normally.</li>
 *   <li>The future carries the matching {@link ConnectionUpdate}
 *       itself, not void, so callers can read the fields that
 *       matched without re-subscribing.</li>
 * </ul>
 * A predicate that throws is treated as a non-match for that
 * update (the bus already logs listener exceptions); the wait
 * continues until a match or the timeout. The listener is removed
 * from the bus as soon as the future completes, by match or by
 * timeout, so a waiter never leaks a subscription.
 *
 * <p>Use the JaWaClient {@code waitForConnectionUpdate} method for
 * the client form; use this class directly when only a bus is
 * available (tests do this). Never wait without a timeout: a
 * non-negative timeout is required.
 */
public final class ConnectionUpdateWaiter {

    private ConnectionUpdateWaiter() {}

    /**
     * Waits for a connection update matching {@code predicate}.
     *
     * @param bus bus to listen on; must not be null
     * @param predicate match test; must not be null
     * @param timeoutMs non-negative wait budget in milliseconds;
     *                  0 means "time out immediately" (only an update
     *                  dispatched before the timeout task runs can
     *                  match, which in practice is none)
     * @return future completing with the first matching update, or
     *         null on timeout; never completes exceptionally for a
     *         timeout or a predicate throw
     * @throws IllegalArgumentException when the bus or predicate is
     *         null, or the timeout is negative
     */
    public static CompletableFuture<ConnectionUpdate> waitFor(
            WaEventBus bus, Predicate<ConnectionUpdate> predicate, long timeoutMs) {
        if (bus == null) throw new IllegalArgumentException("bus must not be null");
        if (predicate == null) throw new IllegalArgumentException("predicate must not be null");
        if (timeoutMs < 0) throw new IllegalArgumentException("timeoutMs must not be negative");
        CompletableFuture<ConnectionUpdate> result = new CompletableFuture<>();
        WaEventListener listener = new WaEventListener() {
            @Override
            public void onConnectionUpdate(ConnectionUpdate update) {
                if (result.isDone()) return;
                boolean match;
                try {
                    match = predicate.test(update);
                } catch (RuntimeException ignored) {
                    match = false;
                }
                if (match) result.complete(update);
            }
        };
        bus.addListener(listener);
        result.whenComplete((value, error) -> bus.removeListener(listener));
        CompletableFuture.runAsync(() -> {}, CompletableFuture.delayedExecutor(timeoutMs, TimeUnit.MILLISECONDS))
            .thenRun(() -> result.complete(null));
        return result;
    }
}
