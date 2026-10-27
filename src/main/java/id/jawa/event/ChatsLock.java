package id.jawa.event;

/**
 * Payload for Baileys {@code chats.lock}.
 * <p>
 * Baileys shape: {@code {id, locked}}.
 * <p>
 * Defined for API completeness; not yet dispatched by JaWaClient (see EVENT_COMPATIBILITY_MATRIX.md).
 *
 * @param id chat JID
 * @param locked whether the chat is locked
 */
public record ChatsLock(String id, boolean locked) {}
