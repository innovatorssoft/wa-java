package id.ja_wa.domain.model;

/**
 * Metadata record for a Meta AI bot persona available on WhatsApp.
 *
 * @param personaId Unique AI bot persona identifier
 * @param botJid WhatsApp service JID associated with the bot persona
 */
public record BotListInfo(
    String personaId,
    String botJid
) {}
