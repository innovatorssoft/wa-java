package id.ja_wa.event;

import java.util.List;

/**
 * Payload for Baileys {@code chats.update}.
 * <p>
 * Baileys shape: {@code Partial<Chat>[]}. During initial sync each entry also
 * carries an internal {@code conditional} function consumed by the Baileys
 * buffer; that function is <em>never</em> serialised or ported as data, so
 * no Java payload record contains a functional field, and this record
 * carries only the chat JIDs until a chat store exists to hold the partials.
 * <p>
 * Increment 2: dispatched (see WaEventListener for the dispatch sites).
 *
 * @param chatIds chat JIDs whose partial updates Baileys emitted
 */
public record ChatsUpdate(List<String> chatIds) {
    public ChatsUpdate {
        chatIds = chatIds == null ? List.of() : List.copyOf(chatIds);
    }
}
