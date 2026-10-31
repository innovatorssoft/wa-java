package id.ja_wa.event;

import java.util.Map;

/**
 * Payload for Baileys {@code presence.update}.
 * <p>
 * Baileys shape: {@code {id, presences: {[participant]: PresenceInfo}}}.
 *
 * @param id JID the presence update is about, taken from the stanza
 *           {@code attrs.from}
 * @param presences map from participant JID to that participant's presence;
 *                  the participant is {@code attrs.participant} when present,
 *                  otherwise {@code attrs.from}
 */
public record PresenceUpdate(
    String id,
    Map<String, PresenceInfo> presences
) {
    public PresenceUpdate {
        presences = presences == null ? Map.of() : Map.copyOf(presences);
    }
}
