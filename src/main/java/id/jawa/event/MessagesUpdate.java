package id.jawa.event;

import id.jawa.proto.Wa;

import java.util.List;

/**
 * Payload for Baileys {@code messages.update}.
 * <p>
 * Baileys shape: {@code {key, update: Partial<WAMessage>}[]} where
 * {@code update} was observed with {@code status}, {@code messageTimestamp},
 * {@code message: null} + stub REVOKE, {@code editedMessage},
 * {@code pollUpdates[]}, {@code eventResponses[]},
 * {@code messageStubParameters} and {@code starred}. The key fields and the
 * scalar update fields are carried here; the nested edited/poll/event
 * payloads are not modelled in Increment 1 (no stored-message layer exists
 * to apply them to).
 * <p>
 * <p>
 * Increment 2: dispatched by JaWaClient for inbound edit/revoke protocol
 * messages as key-only updates (see WaEventListener).
 * <p>
 * Increment 6: the community V4 invite-accept expiry update is also
 * carried here. Baileys' {@code communityAcceptInviteV4} emits
 * {@code messages.update} with
 * {@code update.message.groupInviteMessage} set to the invite message
 * rewritten as expired; that payload is the new trailing
 * {@code groupInviteMessage} component (null for every other update
 * kind). The seven-argument constructor is kept as a delegating
 * overload so existing call sites are unaffected.
 * <p>
 * Increment 10c: the decrypt-failure placeholder-resend update is
 * also carried here. Baileys' decrypt-failure branch emits
 * {@code messages.update} with
 * {@code update.messageStubParameters} set to
 * {@code [NO_MESSAGE_FOUND_ERROR_TEXT, requestId]} once the PDO
 * resend send succeeds; that payload is the new trailing
 * {@code messageStubParameters} component (null for every other
 * update kind). The eight-argument constructor is kept as a
 * delegating overload so existing call sites are unaffected.
 *
 * @param updates message updates
 */
public record MessagesUpdate(List<MessageUpdate> updates) {
    public MessagesUpdate {
        updates = updates == null ? List.of() : List.copyOf(updates);
    }

    /**
     * One message update.
     *
     * @param messageId Baileys key {@code id}
     * @param remoteJid Baileys key {@code remoteJid}
     * @param fromMe Baileys key {@code fromMe}
     * @param participant Baileys key {@code participant}, null for 1:1 chats
     * @param status Baileys update {@code status}, null when not carried
     * @param messageTimestamp Baileys update {@code messageTimestamp} (epoch seconds), null when not carried
     * @param starred Baileys update {@code starred}, null when not carried
     * @param groupInviteMessage Baileys {@code update.message.groupInviteMessage},
     *                           non-null only for the community V4 invite-accept expiry update
     * @param messageStubParameters Baileys {@code update.messageStubParameters},
     *                              non-null only for the decrypt-failure placeholder-resend
     *                              update, where it is {@code [NO_MESSAGE_FOUND_ERROR_TEXT, requestId]}
     */
    public record MessageUpdate(String messageId, String remoteJid, boolean fromMe, String participant,
                                String status, Long messageTimestamp, Boolean starred,
                                Wa.Message.GroupInviteMessage groupInviteMessage,
                                List<String> messageStubParameters) {
        public MessageUpdate {
            messageStubParameters = messageStubParameters == null ? null : List.copyOf(messageStubParameters);
        }

        /** Pre-Increment 10c constructor: same update with no stub-parameters payload. */
        public MessageUpdate(String messageId, String remoteJid, boolean fromMe, String participant,
                             String status, Long messageTimestamp, Boolean starred,
                             Wa.Message.GroupInviteMessage groupInviteMessage) {
            this(messageId, remoteJid, fromMe, participant, status, messageTimestamp, starred,
                groupInviteMessage, null);
        }

        /** Pre-Increment 6 constructor: same update with no group-invite payload. */
        public MessageUpdate(String messageId, String remoteJid, boolean fromMe, String participant,
                             String status, Long messageTimestamp, Boolean starred) {
            this(messageId, remoteJid, fromMe, participant, status, messageTimestamp, starred, null);
        }
    }
}
