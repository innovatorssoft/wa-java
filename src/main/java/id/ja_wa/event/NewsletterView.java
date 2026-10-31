package id.ja_wa.event;

/**
 * Payload for Baileys {@code newsletter.view}.
 * <p>
 * Baileys shape: {@code {id: newsletterJid, server_id: message_id, count: number}}.
 * <p>
 * Increment 2: dispatched (see WaEventListener for the dispatch sites).
 *
 * @param id newsletter JID
 * @param serverId message server id
 * @param count view count
 */
public record NewsletterView(String id, String serverId, long count) {}
