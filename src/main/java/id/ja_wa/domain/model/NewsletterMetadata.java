package id.ja_wa.domain.model;

/**
 * Metadata and administrative properties describing a WhatsApp newsletter (channel).
 *
 * @param id The newsletter JID (e.g. 12345@newsletter)
 * @param name Channel display name
 * @param description Channel descriptive text
 * @param inviteCode Channel alphanumeric invite code
 * @param creationTime Epoch timestamp seconds of channel creation
 * @param subscriberCount Total number of followers
 * @param role Current user role in this newsletter ("owner", "admin", "subscriber", "guest")
 * @param muted Whether notifications are muted for the current user
 * @param pictureUrl Direct HTTP URL to the channel profile avatar
 */
public record NewsletterMetadata(
    String id,
    String name,
    String description,
    String inviteCode,
    long creationTime,
    int subscriberCount,
    String role,
    boolean muted,
    String pictureUrl
) {}
