package id.jawa.event;

import java.util.List;

/**
 * Payload for Baileys {@code chats.upsert}.
 * <p>
 * Baileys shape: {@code Chat[]}. Chat JIDs are carried here; wa-java has no
 * chat store yet, so the full {@code Chat} objects are not modelled in Increment 1.
 * <p>
 * Increment 2: dispatched (see WaEventListener for the dispatch sites).
 *
 * @param chatIds chat JIDs from the Baileys {@code Chat[]} array
 */
public record ChatsUpsert(List<String> chatIds) {
    public ChatsUpsert {
        chatIds = chatIds == null ? List.of() : List.copyOf(chatIds);
    }
}
