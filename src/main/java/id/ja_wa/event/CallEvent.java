package id.ja_wa.event;

/**
 * Payload for Baileys {@code call} (one element of the Baileys {@code Call[]} array).
 * <p>
 * Baileys shape: {@code {chatId, from, callerPn?, id, date, offline, status,
 * isVideo?, isGroup?, groupJid?, latencyMs?}}, where {@code date} is a JS
 * {@code Date} built as {@code new Date(+attrs.t * 1000)}.
 *
 * @param chatId chat JID, from the call stanza {@code attrs.from}
 * @param from caller JID, from the info child {@code attrs.from} or
 *             {@code attrs.call-creator} when the former is absent; null if neither is present
 * @param callerPn Baileys {@code callerPn}, from the info child
 *                 {@code attrs.caller_pn}; null when absent (may be backfilled
 *                 from a cached offer for the same call id)
 * @param id call id, from the info child {@code attrs.call-id}
 * @param dateEpochSec call date as epoch <em>seconds</em>, parsed from the
 *                     stanza {@code attrs.t}; Baileys' JS {@code Date} is
 *                     this value multiplied by 1000 (see {@link #dateEpochMs()})
 * @param offline whether the stanza carried an {@code offline} attribute
 * @param status Baileys call status (see {@link CallStatus})
 * @param isVideo whether the offer carried a {@code video} child; null when
 *                unknown (non-offer events without a cached offer)
 * @param isGroup whether the call is a group call (info {@code type=group}
 *                or a {@code group-jid} attribute); null when unknown
 * @param groupJid group JID from the info child {@code group-jid}, null when absent
 * @param latencyMs Baileys {@code latencyMs}, set only for
 *                  {@link CallStatus#RELAYLATENCY} events when the info
 *                  child carries a finite {@code latency} /
 *                  {@code latency_ms} / {@code latency-ms} value; null otherwise
 */
public record CallEvent(
    String chatId,
    String from,
    String callerPn,
    String id,
    long dateEpochSec,
    boolean offline,
    CallStatus status,
    Boolean isVideo,
    Boolean isGroup,
    String groupJid,
    Long latencyMs
) {

    /**
     * @return the Baileys JS {@code Date} value for this call as epoch
     *         milliseconds ({@code dateEpochSec * 1000})
     */
    public long dateEpochMs() {
        return dateEpochSec * 1000L;
    }
}
