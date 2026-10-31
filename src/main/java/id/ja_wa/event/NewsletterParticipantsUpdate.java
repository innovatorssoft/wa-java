package id.ja_wa.event;

/**
 * Payload for Baileys {@code newsletter-participants.update}.
 * <p>
 * Baileys shape: {@code {id, author?, user, action, new_role?}} (the mex path
 * hardcodes {@code {new_role: 'ADMIN', action: 'promote', author: node.attrs.from}};
 * the stanza path uses {@code child.attrs.{jid, action, role}}).
 * <p>
 * Increment 2: dispatched (see WaEventListener for the dispatch sites).
 *
 * @param id newsletter JID
 * @param author author JID, null when absent
 * @param user affected user JID
 * @param action Baileys action string
 * @param newRole new role string, null when absent
 */
public record NewsletterParticipantsUpdate(String id, String author, String user, String action, String newRole) {}
