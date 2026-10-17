package id.jawa.domain.model;

/**
 * Pending participant join request awaiting approval by group administrators.
 *
 * @param jid Phone number or LID JID of the requesting user
 * @param requestedAt Epoch timestamp seconds of the join request submission
 */
public record GroupJoinRequest(
    String jid,
    long requestedAt
) {}
