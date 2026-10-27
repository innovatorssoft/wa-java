package id.jawa.event;

/**
 * Payload for Baileys {@code group.join-request}.
 * <p>
 * Baileys shape: {@code {id, author, authorPn, authorUsername, participant: lid,
 * participantPn: pn, action, method}}.
 * <p>
 * Increment 2: dispatched (see WaEventListener for the dispatch sites).
 *
 * @param id group JID
 * @param author author JID
 * @param authorPn author phone-number JID, null when absent
 * @param authorUsername author username, null when absent
 * @param participant requesting participant LID JID
 * @param participantPn requesting participant phone-number JID, null when absent
 * @param action Baileys action string
 * @param method Baileys method string, null when absent
 */
public record GroupJoinRequest(String id, String author, String authorPn, String authorUsername,
                               String participant, String participantPn, String action, String method) {}
