package id.jawa.event;

/**
 * Payload for Baileys {@code group.member-tag.update}.
 * <p>
 * Baileys shape: {@code {groupId, label, participant, participantAlt,
 * messageTimestamp: number}} (from the GROUP_MEMBER_LABEL_CHANGE protocol message).
 * <p>
 * Increment 2: dispatched (see WaEventListener for the dispatch sites).
 *
 * @param groupId group JID
 * @param label member label string
 * @param participant participant JID
 * @param participantAlt alternate participant JID, null when absent
 * @param messageTimestamp message timestamp (epoch seconds)
 */
public record GroupMemberTagUpdate(String groupId, String label, String participant,
                                   String participantAlt, long messageTimestamp) {}
