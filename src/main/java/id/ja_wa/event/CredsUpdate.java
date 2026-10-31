package id.ja_wa.event;

/**
 * Payload for Baileys {@code creds.update}.
 * <p>
 * Baileys {@code creds.update} is polymorphic: it carries either a full
 * creds object or a one-field partial ({@code me}, {@code accountSettings},
 * {@code myAppStateKeyId}, {@code signedPreKey}, {@code accountSyncCounter},
 * {@code lastAccountSyncTimestamp}, {@code processedHistoryMessages}, or
 * {@code accountSettings.defaultDisappearingMode}). Consumers must
 * <em>merge</em>, not replace, their stored creds on receiving this event.
 * <p>
 * This record is deliberately kept to the fields JaWaClient actually emits
 * today (the paired {@code me} identity) plus the scalar partial fields
 * that are cheap to wire later. It does not yet carry the full Baileys
 * creds object or the non-scalar partials; those fields stay null/absent
 * until a dispatch site for them exists, rather than being guessed.
 *
 * @param meJid paired account JID (Baileys {@code me.id}), null when this
 *              update does not carry an identity
 * @param pushName paired account push name (Baileys {@code me.name}),
 *                 null when not carried
 * @param accountSyncCounter Baileys {@code accountSyncCounter}, null when not carried
 * @param lastAccountSyncTimestamp Baileys {@code lastAccountSyncTimestamp}
 *                                 (epoch seconds, as stored by Baileys),
 *                                 null when not carried
 * @param myAppStateKeyId Baileys {@code myAppStateKeyId}, null when not carried
 */
public record CredsUpdate(
    String meJid,
    String pushName,
    Long accountSyncCounter,
    Long lastAccountSyncTimestamp,
    String myAppStateKeyId
) {

    /**
     * @param meJid paired account JID
     * @param pushName paired account push name
     * @return a {@code creds.update} carrying only the paired identity
     */
    public static CredsUpdate paired(String meJid, String pushName) {
        return new CredsUpdate(meJid, pushName, null, null, null);
    }
}
