package id.jawa.event;

/**
 * Payload for Baileys {@code connection.update}.
 * <p>
 * Baileys emits <em>partials</em>, not snapshots: any field may be absent
 * (null) on a given emission, and consumers must merge rather than replace
 * their connection state. The fields below mirror the object passed at the
 * Baileys emit sites: {@code connection} is {@code open} /
 * {@code connecting} / {@code close}; {@code qr} is present only during
 * pairing; {@code receivedPendingNotifications}, {@code isNewLogin} and
 * {@code isOnline} are booleans; {@code lastDisconnect} is
 * {@code {error, date}}; {@code reachoutTimeLock} is
 * {@code {isActive, timeEnforcementEnds?, enforcementType}}.
 *
 * @param connection connection state, or null for a partial update that
 *                   carries only other fields (e.g. the isNewLogin update
 *                   emitted on pairing)
 * @param qr QR payload string, present only during pairing, otherwise null
 * @param receivedPendingNotifications Baileys {@code receivedPendingNotifications},
 *                                     null when not carried by this update
 * @param isNewLogin Baileys {@code isNewLogin}, null when not carried
 * @param isOnline Baileys {@code isOnline} (from the chats path), null when
 *                 not carried
 * @param lastDisconnectError Baileys {@code lastDisconnect.error} as a
 *                            diagnostic string, null when not carried
 * @param lastDisconnectDateEpochMs Baileys {@code lastDisconnect.date} as
 *                                  epoch milliseconds, null when not carried
 * @param reachoutTimeLock Baileys {@code reachoutTimeLock}, null when not carried
 */
public record ConnectionUpdate(
    ConnectionState connection,
    String qr,
    Boolean receivedPendingNotifications,
    Boolean isNewLogin,
    Boolean isOnline,
    String lastDisconnectError,
    Long lastDisconnectDateEpochMs,
    ReachoutTimeLock reachoutTimeLock
) {

    /** Baileys {@code connection} values, plus UNKNOWN for anything else. */
    public enum ConnectionState {
        /** Baileys {@code open}. */
        OPEN,
        /** Baileys {@code connecting}. */
        CONNECTING,
        /** Baileys {@code close}. */
        CLOSE,
        /** Any connection value outside the three Baileys values. */
        UNKNOWN
    }

    /**
     * Baileys {@code reachoutTimeLock}.
     *
     * @param isActive whether the reach-out time lock is active
     * @param timeEnforcementEndsEpochMs enforcement end as epoch milliseconds,
     *                                     null when Baileys omits it
     * @param enforcementType Baileys {@code enforcementType} string, null when omitted
     */
    public record ReachoutTimeLock(
        boolean isActive,
        Long timeEnforcementEndsEpochMs,
        String enforcementType
    ) {}

    /** @return an {@code open} update with no other fields set. */
    public static ConnectionUpdate open() {
        return new ConnectionUpdate(ConnectionState.OPEN, null, null, null, null, null, null, null);
    }

    /** @return a {@code connecting} update with no other fields set. */
    public static ConnectionUpdate connecting() {
        return new ConnectionUpdate(ConnectionState.CONNECTING, null, null, null, null, null, null, null);
    }

    /**
     * @param qr QR payload string
     * @return a partial update carrying only {@code qr}
     */
    public static ConnectionUpdate qr(String qr) {
        return new ConnectionUpdate(null, qr, null, null, null, null, null, null);
    }

    /**
     * @param isNewLogin value of {@code isNewLogin}
     * @return a partial update carrying only {@code isNewLogin}
     */
    public static ConnectionUpdate isNewLogin(boolean isNewLogin) {
        return new ConnectionUpdate(null, null, null, isNewLogin, null, null, null, null);
    }

    /**
     * @param error diagnostic string for {@code lastDisconnect.error}
     * @return a {@code close} update whose disconnect date is now
     */
    public static ConnectionUpdate close(String error) {
        return close(error, System.currentTimeMillis());
    }

    /**
     * @param error diagnostic string for {@code lastDisconnect.error}
     * @param dateEpochMs epoch milliseconds for {@code lastDisconnect.date}, may be null
     * @return a {@code close} update
     */
    public static ConnectionUpdate close(String error, Long dateEpochMs) {
        return new ConnectionUpdate(ConnectionState.CLOSE, null, null, null, null,
            error, dateEpochMs, null);
    }
}
