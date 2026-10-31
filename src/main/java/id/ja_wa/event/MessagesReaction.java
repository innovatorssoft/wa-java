package id.ja_wa.event;

import java.util.List;

/**
 * Payload for Baileys {@code messages.reaction}.
 * <p>
 * Baileys shape: {@code {key, reaction: ReactionMessage}[]}.
 * <p>
 * Increment 2: dispatched (see WaEventListener for the dispatch sites).
 *
 * @param reactions reactions
 */
public record MessagesReaction(List<MessageReaction> reactions) {
    public MessagesReaction {
        reactions = reactions == null ? List.of() : List.copyOf(reactions);
    }

    /**
     * One reaction.
     *
     * @param messageId key {@code id}
     * @param remoteJid key {@code remoteJid}
     * @param fromMe key {@code fromMe}
     * @param participant key {@code participant}, null otherwise
     * @param reactionCode reaction emoji/text code, empty when the reaction was removed
     * @param senderJid JID of the reacting user, null when unknown
     */
    public record MessageReaction(String messageId, String remoteJid, boolean fromMe, String participant,
                                  String reactionCode, String senderJid) {}
}
