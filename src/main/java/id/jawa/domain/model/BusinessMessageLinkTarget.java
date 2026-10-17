package id.jawa.domain.model;

/**
 * Resolved business target information from a WhatsApp wa.me / message shortlink.
 *
 * @param jid Target merchant WhatsApp business JID
 * @param pushName Public business name
 * @param message Pre-filled draft message embedded in the shortlink
 * @param isSigned Whether the shortlink payload was cryptographically signed by Meta
 * @param verifiedName Official verified enterprise business name, if present
 * @param verifiedLevel Trust verification level string
 */
public record BusinessMessageLinkTarget(
    String jid,
    String pushName,
    String message,
    boolean isSigned,
    String verifiedName,
    String verifiedLevel
) {}
