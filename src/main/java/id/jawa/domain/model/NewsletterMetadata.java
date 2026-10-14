package id.jawa.domain.model;

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
