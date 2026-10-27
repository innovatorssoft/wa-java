package id.jawa.event;

/**
 * Payload for Baileys {@code messaging-history.status}.
 * <p>
 * Baileys shape: {@code {syncType: RECENT, status: 'complete'|'paused', explicit: bool}}.
 * <p>
 * Defined for API completeness; not yet dispatched by JaWaClient (see EVENT_COMPATIBILITY_MATRIX.md).
 *
 * @param syncType Baileys {@code syncType}
 * @param status Baileys {@code status} ({@code complete} or {@code paused})
 * @param explicit Baileys {@code explicit} flag
 */
public record MessagingHistoryStatus(String syncType, String status, boolean explicit) {}
