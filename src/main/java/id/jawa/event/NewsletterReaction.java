package id.jawa.event;

/**
 * Payload for Baileys {@code newsletter.reaction}.
 * <p>
 * Baileys shape: {@code {id: newsletterJid, server_id: message_id,
 * reaction: {code: string, count: 1}}}.
 * <p>
 * Increment 2: dispatched (see WaEventListener for the dispatch sites).
 *
 * @param id newsletter JID
 * @param serverId message server id
 * @param reactionCode reaction code string
 * @param count reaction count (Baileys always emits 1 here)
 */
public record NewsletterReaction(String id, String serverId, String reactionCode, long count) {}
