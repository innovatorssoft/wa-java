package id.ja_wa.domain.model;

/**
 * Resolved recipient target details from scanning a WhatsApp contact QR code or link.
 *
 * @param jid Target user WhatsApp JID
 * @param pushName Contact display name configured by the user
 * @param type Registration link type (e.g. "contact")
 */
public record ContactQrTarget(
    String jid,
    String pushName,
    String type
) {}
