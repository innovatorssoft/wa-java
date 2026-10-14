package id.jawa.domain.model;

public record BusinessMessageLinkTarget(
    String jid,
    String pushName,
    String message,
    boolean isSigned,
    String verifiedName,
    String verifiedLevel
) {}
