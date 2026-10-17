package id.jawa.domain.model;

/**
 * Verification result indicating whether a telephone contact is registered on WhatsApp.
 *
 * @param jid Normalized WhatsApp JID of the verified user
 * @param isOnWhatsApp True if the phone number possesses an active WhatsApp account
 */
public record ContactStatus(
    String jid,
    boolean isOnWhatsApp
) {}
