package id.jawa.event;

/**
 * Payload for Baileys {@code newsletter-settings.update}.
 * <p>
 * Baileys shape: {@code {id: newsletterJid, update: settings}}; the stanza
 * path carries only {@code name?} / {@code description?}, while the mex path
 * carries {@code update.settings} verbatim. Only the stanza-path fields are
 * modelled here; the verbatim mex settings object is not modelled in Increment 1.
 * <p>
 * Increment 2: dispatched (see WaEventListener for the dispatch sites).
 *
 * @param id newsletter JID
 * @param name newsletter name, null when not carried
 * @param description newsletter description, null when not carried
 */
public record NewsletterSettingsUpdate(String id, String name, String description) {}
