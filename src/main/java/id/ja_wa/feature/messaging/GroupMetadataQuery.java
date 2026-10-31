package id.ja_wa.feature.messaging;

import id.ja_wa.domain.model.Jid;
import id.ja_wa.protocol.codec.BinaryNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure query builders and response parsers for Baileys'
 * {@code groupMetadata} / {@code groupFetchAllParticipating}
 * ({@code lib/Socket/groups.js}).
 * <p>
 * This class performs no I/O and holds no transport: it only builds the
 * {@code iq} stanza a client must send and parses the stanza a client
 * receives. Live execution needs JaWaClient wiring (its {@code query}
 * path), which is deliberately not done here — see the wiring note in
 * the class Javadoc of {@link StatusMentionBuilder} for the same pattern.
 * <p>
 * Query shape (Baileys {@code groupQuery} + {@code groupMetadata}):
 * {@code <iq type="get" xmlns="w:g2" to="<groupJid>"><query request=
 * "interactive"/></iq>}. The participating-list query is
 * {@code GroupListQuery.buildQuery} and is reused, not duplicated.
 * <p>
 * Parser shape (Baileys {@code extractGroupMetadata}): the response's
 * {@code <group>} child is parsed field by field; a missing
 * {@code <group>} child is an error, and an {@code <error>} child is
 * surfaced with its code and text instead of a generic failure — the
 * same behaviour as Baileys' {@code HttpError} throw (wa-java has no
 * {@code HttpError} type, so an {@link IllegalStateException} carrying
 * the code is used). LID/PN participant mapping is <em>not</em> stored
 * here: Baileys itself leaves a {@code TODO: Store LID MAPPINGS} at
 * this point, and wa-java's {@code GroupListQuery.parseSingleGroup}
 * already records the mappings it can derive.
 */
public final class GroupMetadataQuery {

    private GroupMetadataQuery() {}

    /** One group participant, as Baileys' {@code extractGroupMetadata} returns it. */
    public record Participant(String id, String phoneNumber, String lid, String username,
                              String admin) {}

    /** Full group metadata, as Baileys' {@code extractGroupMetadata} returns it. */
    public record GroupMetadata(
        String id,
        // Baileys field name is "notify"; "notify" cannot be a record
        // component because it would clash with Object.notify().
        String notifyAttr,
        String addressingMode,
        String subject,
        String subjectOwner,
        String subjectOwnerPn,
        String subjectOwnerUsername,
        long subjectTime,
        int size,
        long creation,
        String owner,
        String ownerPn,
        String ownerUsername,
        String ownerCountryCode,
        String desc,
        String descId,
        String descOwner,
        String descOwnerPn,
        String descOwnerUsername,
        Long descTime,
        String linkedParent,
        boolean restrict,
        boolean announce,
        boolean isCommunity,
        boolean isCommunityAnnounce,
        boolean joinApprovalMode,
        boolean memberAddMode,
        List<Participant> participants,
        Integer ephemeralDuration
    ) {
        public GroupMetadata {
            participants = participants == null ? List.of() : List.copyOf(participants);
        }
    }

    /**
     * Builds the {@code groupMetadata} query stanza.
     *
     * @param iqId stanza id assigned by the caller
     * @param groupJid group JID
     * @return {@code iq} node to send
     */
    public static BinaryNode buildQuery(String iqId, String groupJid) {
        return GroupListQuery.buildGroupInfoQuery(iqId, groupJid);
    }

    /**
     * Builds the participating-groups query stanza (Baileys
     * {@code groupFetchAllParticipating}).
     *
     * @param iqId stanza id assigned by the caller
     * @return {@code iq} node to send
     */
    public static BinaryNode buildParticipatingQuery(String iqId) {
        return GroupListQuery.buildQuery(iqId);
    }

    /**
     * Parses a single-group metadata response (the {@code groupMetadata}
     * result, or a synthetic result wrapping one {@code <group>} node as
     * Baileys does for participating groups).
     *
     * @param result response stanza
     * @return parsed metadata
     * @throws IllegalStateException on a missing {@code <group>} child or
     *         a missing group id (an {@code <error>} child is reported
     *         with its code and text)
     */
    public static GroupMetadata parse(BinaryNode result) {
        if (result == null) throw new IllegalStateException("null group metadata response");
        BinaryNode group = result.child("group");
        if (group == null) {
            BinaryNode error = result.child("error");
            if (error != null) {
                String code = error.attr("code");
                String text = error.attr("text");
                throw new IllegalStateException("group metadata query failed"
                    + (code != null ? " (code " + code + ")" : "")
                    + (text != null ? ": " + text : ""));
            }
            throw new IllegalStateException("invalid group metadata response: missing <group> node");
        }
        return parseGroupNode(group);
    }

    /**
     * Parses a participating-groups response into one metadata record per
     * {@code <group>} child of the {@code <groups>} child (Baileys
     * {@code groupFetchAllParticipating}).
     *
     * @param result response stanza; a missing {@code <groups>} child yields empty
     * @return parsed metadata, in stanza order
     */
    public static List<GroupMetadata> parseParticipating(BinaryNode result) {
        List<GroupMetadata> out = new ArrayList<>();
        if (result == null) return out;
        BinaryNode groups = result.child("groups");
        if (groups == null) return out;
        for (BinaryNode group : groups.children("group")) {
            out.add(parseGroupNode(group));
        }
        return out;
    }

    /** Parses one {@code <group>} node (shared by {@link #parse} and {@link #parseParticipating}). */
    public static GroupMetadata parseGroupNode(BinaryNode group) {
        if (group == null) throw new IllegalStateException("null <group> node");
        String rawId = group.attr("id");
        if (rawId == null || rawId.isEmpty()) {
            throw new IllegalStateException("invalid group metadata response: missing group id");
        }
        String groupId = rawId.contains("@") ? rawId : rawId + "@" + Jid.SERVER_GROUP;

        BinaryNode descChild = group.child("description");
        String desc = null;
        String descId = null;
        String descOwner = null;
        String descOwnerPn = null;
        String descOwnerUsername = null;
        Long descTime = null;
        if (descChild != null) {
            BinaryNode body = descChild.child("body");
            desc = body == null ? null : body.textContent();
            descOwner = normalized(descChild.attr("participant"));
            descOwnerPn = normalized(descChild.attr("participant_pn"));
            descOwnerUsername = descChild.attr("participant_username");
            descTime = parseLongOrNull(descChild.attr("t"));
            descId = descChild.attr("id");
        }

        BinaryNode eph = group.child("ephemeral");
        Integer ephemeralDuration = null;
        if (eph != null) {
            Long v = parseLongOrNull(eph.attr("expiration"));
            if (v != null) ephemeralDuration = v.intValue();
        }

        List<BinaryNode> participantNodes = group.children("participant");
        List<Participant> participants = new ArrayList<>();
        for (BinaryNode p : participantNodes) {
            String pj = p.attr("jid");
            String phoneNumber = pj != null && pj.endsWith("@" + Jid.SERVER_LID)
                && isPn(p.attr("phone_number")) ? p.attr("phone_number") : null;
            String lid = pj != null && isPn(pj) && isLid(p.attr("lid")) ? p.attr("lid") : null;
            String username = p.attr("participant_username");
            if (username == null) username = p.attr("username");
            participants.add(new Participant(pj, phoneNumber, lid, username, p.attr("type")));
        }

        BinaryNode memberAddModeNode = group.child("member_add_mode");
        boolean memberAddMode = memberAddModeNode != null
            && "all_member_add".equals(memberAddModeNode.textContent());

        String sizeAttr = group.attr("size");
        int size = sizeAttr != null ? (int) parseLong(sizeAttr, participantNodes.size())
            : participantNodes.size();

        return new GroupMetadata(
            groupId,
            group.attr("notify"),
            "lid".equals(group.attr("addressing_mode")) ? "lid" : "pn",
            group.attr("subject"),
            normalized(group.attr("s_o")),
            normalized(group.attr("s_o_pn")),
            group.attr("s_o_username"),
            parseLong(group.attr("s_t"), 0L),
            size,
            parseLong(group.attr("creation"), 0L),
            normalized(group.attr("creator")),
            normalized(group.attr("creator_pn")),
            group.attr("creator_username"),
            group.attr("creator_country_code"),
            desc, descId, descOwner, descOwnerPn, descOwnerUsername, descTime,
            group.child("linked_parent") == null ? null : group.child("linked_parent").attr("jid"),
            group.child("locked") != null,
            group.child("announcement") != null,
            group.child("parent") != null,
            group.child("default_sub_group") != null,
            group.child("membership_approval_mode") != null,
            memberAddMode,
            participants,
            ephemeralDuration);
    }

    private static boolean isPn(String jid) {
        return jid != null && jid.endsWith("@" + Jid.SERVER_WHATSAPP);
    }

    private static boolean isLid(String jid) {
        return jid != null && jid.endsWith("@" + Jid.SERVER_LID);
    }

    /** Baileys {@code jidNormalizedUser}: bare {@code user@server}, null-safe. */
    private static String normalized(String jid) {
        if (jid == null) return null;
        try {
            Jid parsed = Jid.parse(jid);
            if (parsed != null) return parsed.toBareString();
        } catch (RuntimeException ignored) {
            // fall through to bare()
        }
        return Jid.bare(jid);
    }

    private static long parseLong(String s, long fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Long parseLongOrNull(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
