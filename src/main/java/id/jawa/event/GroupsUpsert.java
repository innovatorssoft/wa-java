package id.jawa.event;

import java.util.List;

/**
 * Payload for Baileys {@code groups.upsert}.
 * <p>
 * Baileys shape: {@code GroupMetadata[]} (spread metadata plus
 * {@code author, authorPn, authorUsername}). The full Baileys metadata has
 * many more fields; this record carries the fields wa-java can source
 * (id, subject, author fields, participant JIDs from the group node).
 * <p>
 * Increment 2: dispatched by JaWaClient for group-create notifications
 * ({@code w:gp2} {@code create}), and by {@code InMemoryStore} when bound.
 *
 * @param groups upserted group metadata
 */
public record GroupsUpsert(List<GroupInfo> groups) {
    public GroupsUpsert {
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    /**
     * Group metadata plus author fields.
     *
     * @param id group JID
     * @param subject group subject, null when absent
     * @param author author JID, null when absent
     * @param authorPn author phone-number JID, null when absent
     * @param authorUsername author username, null when absent
     * @param participantJids participant JIDs from the group node, empty
     *                        when not carried
     */
    public record GroupInfo(String id, String subject, String author, String authorPn, String authorUsername,
                            List<String> participantJids) {
        public GroupInfo {
            participantJids = participantJids == null ? List.of() : List.copyOf(participantJids);
        }

        /** Convenience constructor without participants. */
        public GroupInfo(String id, String subject, String author, String authorPn, String authorUsername) {
            this(id, subject, author, authorPn, authorUsername, List.of());
        }
    }
}
