package id.jawa.event;

import java.util.List;

/**
 * Payload for Baileys {@code group-participants.update}.
 * <p>
 * Baileys shape: {@code {id, author, authorPn, authorUsername, participants,
 * action: 'add'|'remove'|'modify'|'promote'|'demote'}} (observed: {@code modify},
 * {@code remove}; the action is stub-derived).
 * <p>
 * Increment 2: dispatched (see WaEventListener for the dispatch sites).
 *
 * @param id group JID
 * @param author author JID
 * @param authorPn author phone-number JID, null when absent
 * @param authorUsername author username, null when absent
 * @param participants affected participant JIDs
 * @param action Baileys action string
 */
public record GroupParticipantsUpdate(String id, String author, String authorPn, String authorUsername,
                                      List<String> participants, String action) {
    public GroupParticipantsUpdate {
        participants = participants == null ? List.of() : List.copyOf(participants);
    }
}
