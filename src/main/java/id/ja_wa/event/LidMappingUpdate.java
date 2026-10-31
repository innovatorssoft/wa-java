package id.ja_wa.event;

/**
 * Payload for Baileys {@code lid-mapping.update}.
 * <p>
 * Baileys shape: a <em>single</em> object {@code {lid, pn}}, not an array.
 * <p>
 * Increment 2: dispatched (see WaEventListener for the dispatch sites).
 *
 * @param lid linked-identity JID
 * @param pn phone-number JID
 */
public record LidMappingUpdate(String lid, String pn) {}
