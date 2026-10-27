package id.jawa.event;

import java.util.List;

/**
 * Payload for Baileys {@code messages.delete}.
 * <p>
 * Baileys shape: {@code {keys: WAMessageKey[]}[]} (the buffer notes an
 * unhandled non-{@code keys} variant). Only the {@code keys} variant is modelled here.
 * <p>
 * <p>
 * Increment 2: dispatched by InMemoryStore when bound (see WaEventListener).
 *
 * @param keys deleted message keys
 */
public record MessagesDelete(List<MessageKey> keys) {
    public MessagesDelete {
        keys = keys == null ? List.of() : List.copyOf(keys);
    }

    /**
     * One Baileys {@code WAMessageKey}.
     * <p>
     * Increment 3: {@code participantAlt} added as a backwards-compatible
     * field. Baileys' {@code WAMessageKey} is a JS-level extension of the
     * proto key, not a WAProto field: {@code participantAlt} is the
     * alternate (LID/PN) form of the participant, set by
     * {@code decode-wa-message.js} for groups and by
     * {@code messages-recv.js} for notifications, and used by
     * {@code getKeyAuthor} ({@code lib/Utils/generics.js}) ahead of
     * {@code participant}. It therefore belongs on this plain Java
     * record, <em>not</em> on the generated {@code Wa.MessageKey}
     * (wa.proto {@code MessageKey}: only {@code remoteJid}/{@code fromMe}/
     * {@code id}/{@code participant}) — adding it there would invent a
     * wire field WhatsApp does not define. The 4-argument constructor is
     * kept and delegates with a null {@code participantAlt}.
     *
     * @param id message id
     * @param remoteJid chat JID
     * @param fromMe whether the message was sent by the account
     * @param participant participant JID in groups, null otherwise
     * @param participantAlt alternate participant JID (LID/PN counterpart),
     *                       null when absent
     */
    public record MessageKey(String id, String remoteJid, boolean fromMe, String participant,
                             String participantAlt) {
        /** Convenience constructor without an alternate participant. */
        public MessageKey(String id, String remoteJid, boolean fromMe, String participant) {
            this(id, remoteJid, fromMe, participant, null);
        }
    }
}
