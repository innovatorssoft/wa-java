package id.jawa.event;

/**
 * Baileys call status, as returned by Baileys
 * {@code getCallStatusFromNode} for the info child's tag.
 * Unknown child tags map to {@link #RINGING}, matching Baileys' default branch.
 */
public enum CallStatus {
    /** Info child {@code offer} or {@code offer_notice}. */
    OFFER,
    /** Info child {@code accept}. */
    ACCEPT,
    /** Info child {@code reject}. */
    REJECT,
    /** Info child {@code terminate} with {@code reason=timeout}. */
    TIMEOUT,
    /** Info child {@code terminate} with any other (or no) reason. */
    TERMINATE,
    /** Info child {@code preaccept}. */
    PREACCEPT,
    /** Info child {@code transport}. */
    TRANSPORT,
    /** Info child {@code relaylatency}. */
    RELAYLATENCY,
    /** Any other info child tag (Baileys default branch). */
    RINGING,
    /** Status could not be determined; not a Baileys status, used only as a safe fallback. */
    UNKNOWN
}
