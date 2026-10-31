package id.ja_wa.domain.model;

/**
 * Represents a child discussion group linked under a parent WhatsApp Community.
 *
 * @param jid Subgroup identifier (e.g. 12345@g.us)
 * @param subject Subgroup name or title
 * @param nameSetAt Epoch timestamp seconds when subgroup name was updated
 * @param defaultSubGroup True if this is the community primary announcement subgroup
 */
public record CommunitySubGroup(
    String jid,
    String subject,
    long nameSetAt,
    boolean defaultSubGroup
) {}
