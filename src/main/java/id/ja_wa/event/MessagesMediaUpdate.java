package id.ja_wa.event;

import java.util.List;

/**
 * Payload for Baileys {@code messages.media-update}.
 * <p>
 * Baileys shape: {@code [decodeMediaRetryNode(node)]} =
 * {@code [{key: {id, remoteJid, fromMe, participant}, errorCode?}]} from
 * {@code mediaretry} notifications.
 * <p>
 * Increment 2: dispatched (see WaEventListener for the dispatch sites).
 *
 * @param updates media-retry updates
 */
public record MessagesMediaUpdate(List<MediaUpdate> updates) {
    public MessagesMediaUpdate {
        updates = updates == null ? List.of() : List.copyOf(updates);
    }

    /**
     * One decoded media-retry update.
     *
     * @param messageId key {@code id}
     * @param remoteJid key {@code remoteJid}
     * @param fromMe key {@code fromMe}
     * @param participant key {@code participant}, null otherwise
     * @param errorCode Baileys {@code errorCode}, null when absent
     */
    /**
     * Increment 9: trailing encrypted-media components. Baileys'
     * {@code decodeMediaRetryNode} ({@code lib/Utils/messages-media.js})
     * carries the re-uploaded media on the success path as
     * {@code media: {ciphertext, iv}}; the Increment 1 payload
     * deliberately omitted them (only {@code errorCode}). They are
     * null when the notification carries no {@code encrypt} child
     * (error path, or a pre-existing construction site, via the
     * delegating 5-arg constructor), so existing behaviour is
     * unchanged; {@code updateMediaMessage} needs them to run
     * Baileys' {@code decryptMediaRetryData} step.
     *
     * @param messageId key {@code id}
     * @param remoteJid key {@code remoteJid}
     * @param fromMe key {@code fromMe}
     * @param participant key {@code participant}, null otherwise
     * @param errorCode Baileys {@code errorCode}, null when absent
     * @param ciphertext encrypted {@code MediaRetryNotification} bytes, null when absent
     * @param iv AES-GCM IV for {@code ciphertext}, null when absent
     */
    public record MediaUpdate(String messageId, String remoteJid, boolean fromMe, String participant,
                              Integer errorCode, byte[] ciphertext, byte[] iv) {
        public MediaUpdate(String messageId, String remoteJid, boolean fromMe, String participant,
                           Integer errorCode) {
            this(messageId, remoteJid, fromMe, participant, errorCode, null, null);
        }
    }
}
