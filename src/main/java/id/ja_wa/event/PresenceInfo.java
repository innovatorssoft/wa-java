package id.ja_wa.event;

/**
 * One participant's presence entry inside a {@link PresenceUpdate}.
 * <p>
 * Baileys shape: {@code {lastKnownPresence?, lastSeen?, groupOnlineCount?}}.
 *
 * @param lastKnownPresence Baileys {@code lastKnownPresence} string, e.g.
 *                          {@code available}, {@code unavailable},
 *                          {@code composing}, {@code recording}, {@code paused}
 *                          (mapped to {@code available} by the parser);
 *                          null only if Baileys omitted it
 * @param lastSeen Baileys {@code lastSeen}, parsed from the stanza
 *                 {@code attrs.last} as epoch <em>seconds</em>; null when the
 *                 stanza has no {@code last} attribute or its value is
 *                 {@code deny}
 * @param groupOnlineCount Baileys {@code groupOnlineCount}, parsed from the
 *                         stanza {@code attrs.count} as an integer; null
 *                         when the stanza has no {@code count} attribute
 */
public record PresenceInfo(
    String lastKnownPresence,
    Long lastSeen,
    Integer groupOnlineCount
) {}
