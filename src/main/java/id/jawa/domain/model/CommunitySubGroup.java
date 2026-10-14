package id.jawa.domain.model;

public record CommunitySubGroup(
    String jid,
    String subject,
    long nameSetAt,
    boolean defaultSubGroup
) {}
