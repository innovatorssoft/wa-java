package id.jawa.event;

import java.util.List;

/**
 * Payload for Baileys {@code chats.delete}.
 * <p>
 * Baileys shape: {@code string[]} (chat JIDs).
 * <p>
 * <p>
 * Increment 2: dispatched by InMemoryStore when bound (see WaEventListener).
 *
 * @param chatIds deleted chat JIDs
 */
public record ChatsDelete(List<String> chatIds) {
    public ChatsDelete {
        chatIds = chatIds == null ? List.of() : List.copyOf(chatIds);
    }
}
