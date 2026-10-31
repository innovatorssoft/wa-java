package id.ja_wa.event;

import id.ja_wa.feature.messaging.MessageReceiver;

import java.util.List;

/**
 * Payload for Baileys {@code messaging-history.set}.
 * <p>
 * Baileys shape: {@code {chats, messages, contacts, pastParticipants?, syncType,
 * progress?, isLatest?, chunkOrder?, peerDataRequestSessionId?}}. The store
 * ignores {@code ON_DEMAND} syncs. Chats and contacts are carried here as
 * JID lists only: wa-java has no chat/contact store yet, so the full
 * {@code Chat[]} / {@code Contact[]} objects are not modelled in Increment 1.
 * <p>
 * Defined for API completeness; not yet dispatched by JaWaClient (see EVENT_COMPATIBILITY_MATRIX.md).
 *
 * @param chatIds chat JIDs from the Baileys {@code chats} array
 * @param messages decoded messages, reusing {@link MessageReceiver.Decoded}
 * @param contactIds contact JIDs from the Baileys {@code contacts} array
 * @param syncType Baileys {@code syncType} (HistorySyncType string)
 * @param progress Baileys {@code progress}, null when not carried
 * @param isLatest Baileys {@code isLatest}, null when not carried
 * @param chunkOrder Baileys {@code chunkOrder}, null when not carried
 * @param peerDataRequestSessionId Baileys {@code peerDataRequestSessionId}, null when not carried
 */
public record MessagingHistorySet(
    List<String> chatIds,
    List<MessageReceiver.Decoded> messages,
    List<String> contactIds,
    String syncType,
    Long progress,
    Boolean isLatest,
    Integer chunkOrder,
    String peerDataRequestSessionId
) {
    public MessagingHistorySet {
        chatIds = chatIds == null ? List.of() : List.copyOf(chatIds);
        messages = messages == null ? List.of() : List.copyOf(messages);
        contactIds = contactIds == null ? List.of() : List.copyOf(contactIds);
    }
}
