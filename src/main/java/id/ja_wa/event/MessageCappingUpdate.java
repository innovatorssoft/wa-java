package id.ja_wa.event;

/**
 * Payload for Baileys {@code message-capping.update}.
 * <p>
 * Baileys carries the raw mex payload verbatim
 * ({@code data.xwa2_notify_new_chat_messages_capping_info_update}); no field
 * list is derivable from the Baileys source tree, so this record carries
 * only the raw JSON string and invents no fields.
 * <p>
 * Defined for API completeness; not yet dispatched by JaWaClient (see EVENT_COMPATIBILITY_MATRIX.md).
 *
 * @param rawJson raw mex payload as a JSON string
 */
public record MessageCappingUpdate(String rawJson) {}
