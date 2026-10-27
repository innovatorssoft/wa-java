package id.jawa.event;

import java.util.List;

/**
 * Payload for Baileys {@code groups.update}.
 * <p>
 * Baileys shape: {@code Partial<GroupMetadata>[]} (plus author fields in the
 * stub path). The fields below are the GroupMetadata partials Baileys
 * actually emits for group-change stubs and that wa-java can source from
 * {@code w:gp2} notifications: subject, description, announce, restrict,
 * invite code, member-add mode and join-approval mode.
 * <p>
 * Increment 2: dispatched by JaWaClient for {@code w:gp2} group-change
 * notifications, and by {@code InMemoryStore} when bound.
 *
 * @param updates partial group updates
 */
public record GroupsUpdate(List<GroupUpdate> updates) {
    public GroupsUpdate {
        updates = updates == null ? List.of() : List.copyOf(updates);
    }

    /**
     * One partial group update; unset fields are null.
     *
     * @param id group JID
     * @param subject group subject, null when not carried
     * @param author author JID, null when absent
     * @param authorPn author phone-number JID, null when absent
     * @param authorUsername author username, null when absent
     * @param description group description, null when not carried
     * @param announce announcement-only mode, null when not carried
     * @param restrict restricted (admin-only) mode, null when not carried
     * @param inviteCode group invite code, null when not carried
     * @param memberAddMode true when any member may add participants,
     *                      false when admin-only, null when not carried
     * @param joinApprovalMode true when join approval is on, null when not carried
     */
    public record GroupUpdate(String id, String subject, String author, String authorPn, String authorUsername,
                              String description, Boolean announce, Boolean restrict, String inviteCode,
                              Boolean memberAddMode, Boolean joinApprovalMode) {
        /** Convenience constructor with only id, subject and author fields. */
        public GroupUpdate(String id, String subject, String author, String authorPn, String authorUsername) {
            this(id, subject, author, authorPn, authorUsername, null, null, null, null, null, null);
        }
    }
}
