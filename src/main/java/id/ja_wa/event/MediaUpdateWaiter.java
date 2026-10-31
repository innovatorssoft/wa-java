package id.ja_wa.event;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Predicate-free wait on {@code messages.media-update} for one message
 * id, mirroring Baileys' {@code bindWaitForEvent(ev,
 * 'messages.media-update')} as used by {@code updateMediaMessage}
 * ({@code lib/Socket/messages-send.js:1040,1070}).
 * <p>
 * Pattern follows {@link ConnectionUpdateWaiter} (Increment 7)
 * deliberately, including its two wa-java divergences from Baileys:
 * the future completes with {@code null} on timeout instead of
 * failing (Baileys' media-update wait has no timeout at all, so any
 * timeout here is wa-java-only), and the future carries the
 * matching {@link MessagesMediaUpdate.MediaUpdate} itself. The
 * listener is removed as soon as the future completes, by match,
 * timeout, or cancellation, so a waiter never leaks a subscription.
 * <p>
 * No dispatch change is needed to hook this in: it subscribes to
 * the client's existing event bus, and the Increment 2 mediaretry
 * notification path already dispatches
 * {@code MESSAGES_MEDIA_UPDATE} on that bus for every parsed
 * mediaretry notification.
 */
public final class MediaUpdateWaiter {

    private MediaUpdateWaiter() {}

    /**
     * Waits for the media-update for {@code messageId}.
     *
     * @param bus bus to listen on; must not be null
     * @param messageId message id to match on {@code MediaUpdate.messageId()}; must not be blank
     * @param timeoutMs non-negative wait budget in milliseconds
     * @return future completing with the first matching update, or
     *         null on timeout; never completes exceptionally for a timeout
     * @throws IllegalArgumentException when the bus is null, the message id is blank,
     *         or the timeout is negative
     */
    public static CompletableFuture<MessagesMediaUpdate.MediaUpdate> waitFor(
            WaEventBus bus, String messageId, long timeoutMs) {
        if (bus == null) throw new IllegalArgumentException("bus must not be null");
        if (messageId == null || messageId.isBlank()) {
            throw new IllegalArgumentException("messageId must not be blank");
        }
        if (timeoutMs < 0) throw new IllegalArgumentException("timeoutMs must not be negative");
        CompletableFuture<MessagesMediaUpdate.MediaUpdate> result = new CompletableFuture<>();
        WaEventListener listener = new WaEventListener() {
            @Override
            public void onMessagesMediaUpdate(MessagesMediaUpdate update) {
                if (result.isDone()) return;
                if (update == null || update.updates() == null) return;
                for (MessagesMediaUpdate.MediaUpdate u : update.updates()) {
                    if (u != null && messageId.equals(u.messageId())) {
                        result.complete(u);
                        return;
                    }
                }
            }
        };
        bus.addListener(listener);
        result.whenComplete((value, error) -> bus.removeListener(listener));
        CompletableFuture.runAsync(() -> {}, CompletableFuture.delayedExecutor(timeoutMs, TimeUnit.MILLISECONDS))
            .thenRun(() -> result.complete(null));
        return result;
    }
}
