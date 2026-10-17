package id.jawa.domain.model;

/**
 * An individual entry in the WhatsApp account blocklist.
 *
 * @param lid The LID (Linked Identity) JID of the blocked entity, if available
 * @param pn The phone number JID of the blocked entity, if available
 * @param active Whether the block rule is currently active
 */
public record BlocklistItem(
    String lid,
    String pn,
    boolean active
) {}
