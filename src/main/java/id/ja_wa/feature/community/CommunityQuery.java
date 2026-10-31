package id.ja_wa.feature.community;

import id.ja_wa.domain.model.Jid;
import id.ja_wa.protocol.codec.BinaryNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Pure builders (and response parsers) for WhatsApp Community IQs.
 *
 * <p>Baileys source: {@code lib/Socket/communities.js}
 * ({@code communityQuery} is an {@code iq} with {@code xmlns w:g2} addressed
 * to the community JID, or to {@code @g.us} for community-level operations).
 * Every builder takes the IQ id explicitly — Baileys generates it with
 * {@code generateMessageTag}; wa-java call sites generate their own id
 * because {@code JaWaClient.newIqId} is private. Builders perform no I/O.
 *
 * <p>Increment 5: {@code communityFetchLinkedGroups}'s
 * community-vs-subgroup resolution is covered by
 * {@link #resolveCommunityJid(String, String)} (pure) plus the
 * {@code GroupService.fetchLinkedGroups} facade, which reads
 * {@code linkedParent} from
 * {@code GroupMetadataQuery.GroupMetadata} (Baileys
 * {@code lib/Socket/groups.js} {@code extractGroupMetadata} carries
 * {@code linkedParent} from the {@code <linked_parent>} child).
 * Likewise {@code communityUpdateDescription} in Baileys first fetches
 * metadata for the previous description id — callers pass it explicitly
 * here (or omit it). The {@code messages.update} expiry side-effect of
 * The {@code messages.update} expiry side-effect of
 * {@code communityAcceptInviteV4} is dispatched by
 * {@code JaWaClient.acceptCommunityInviteV4} (via the
 * {@code GroupService} delegate) using {@link #expireInviteMessage};
 * the {@code CB:ib,,dirty} refetch remains a parent hook.
 */
public final class CommunityQuery {

    private CommunityQuery() {}

    /** One participant affected by a membership action, mirroring Baileys' {@code {status, jid}} rows. */
    public record ParticipantResult(String jid, String status) {}

    /** One subgroup row from a {@code <sub_groups>} response (Baileys {@code communityFetchLinkedGroups}). */
    public record LinkedGroup(String id, String subject, Long creation, String owner, Long size) {}

    /**
     * Baileys {@code communityFetchLinkedGroups} result: the resolved
     * community JID, whether the queried JID was itself the community,
     * and its linked subgroups.
     */
    public record LinkedGroupsResult(String communityJid, boolean isCommunity,
                                     List<LinkedGroup> linkedGroups) {
        public LinkedGroupsResult {
            linkedGroups = linkedGroups == null ? List.of() : List.copyOf(linkedGroups);
        }
    }

    /**
     * Baileys {@code communityFetchLinkedGroups} resolution, pure form:
     * a subgroup's community is its metadata's {@code linkedParent};
     * a JID with no {@code linkedParent} is itself the community.
     *
     * @param jid queried group/community JID
     * @param linkedParent {@code linkedParent} from the queried JID's
     *         group metadata, or null when it has none
     * @return the community JID to fetch subgroups for
     */
    public static String resolveCommunityJid(String jid, String linkedParent) {
        return linkedParent != null && !linkedParent.isBlank() ? linkedParent : jid;
    }

    /** Baileys {@code communityCreate}. {@code descriptionId} is Baileys' {@code generateMessageID().substring(0, 12)}. */
    public static BinaryNode buildCreate(String iqId, String subject, String description, String descriptionId) {
        require(iqId, "iqId");
        require(subject, "subject");
        require(descriptionId, "descriptionId");
        BinaryNode body = new BinaryNode("body", Map.of(), description == null ? "" : description);
        BinaryNode desc = new BinaryNode("description", Map.of("id", descriptionId), List.of(body));
        BinaryNode parent = new BinaryNode("parent",
            Map.of("default_membership_approval_mode", "request_required"), null);
        BinaryNode create = new BinaryNode("create", Map.of("subject", subject),
            List.of(desc, parent,
                BinaryNode.of("allow_non_admin_sub_group_creation"),
                BinaryNode.of("create_general_chat")));
        return iq(iqId, Jid.SERVER_GROUP, "set", create);
    }

    /** Baileys {@code communityCreateGroup}: a subgroup linked to a parent community at creation. */
    public static BinaryNode buildCreateGroup(String iqId, String subject, List<String> participants,
                                              String parentCommunityJid, String key) {
        require(iqId, "iqId");
        require(subject, "subject");
        require(parentCommunityJid, "parentCommunityJid");
        require(key, "key");
        List<BinaryNode> children = new ArrayList<>();
        if (participants != null) {
            for (String jid : participants) children.add(BinaryNode.of("participant", Map.of("jid", jid)));
        }
        children.add(BinaryNode.of("linked_parent", Map.of("jid", parentCommunityJid)));
        BinaryNode create = new BinaryNode("create", Map.of("subject", subject, "key", key), children);
        return iq(iqId, Jid.SERVER_GROUP, "set", create);
    }

    /** Baileys {@code communityMetadata}: get + {@code <query request="interactive"/>}. */
    public static BinaryNode buildMetadata(String iqId, String communityJid) {
        require(iqId, "iqId");
        require(communityJid, "communityJid");
        return iq(iqId, communityJid, "get",
            new BinaryNode("query", Map.of("request", "interactive"), null));
    }

    /** Baileys {@code communityFetchAllParticipating}. */
    public static BinaryNode buildFetchAllParticipating(String iqId) {
        require(iqId, "iqId");
        BinaryNode participating = new BinaryNode("participating", Map.of(),
            List.of(BinaryNode.of("participants"), BinaryNode.of("description")));
        return iq(iqId, Jid.SERVER_GROUP, "get", participating);
    }

    /** Baileys {@code communityLeave}. */
    public static BinaryNode buildLeave(String iqId, String communityId) {
        require(iqId, "iqId");
        require(communityId, "communityId");
        BinaryNode community = BinaryNode.of("community", Map.of("id", communityId));
        return iq(iqId, Jid.SERVER_GROUP, "set", new BinaryNode("leave", Map.of(), List.of(community)));
    }

    /** Baileys {@code communityUpdateSubject}. */
    public static BinaryNode buildUpdateSubject(String iqId, String communityJid, String subject) {
        require(iqId, "iqId");
        require(communityJid, "communityJid");
        require(subject, "subject");
        return iq(iqId, communityJid, "set", new BinaryNode("subject", Map.of(), subject));
    }

    /**
     * Baileys {@code communityUpdateDescription}. A null {@code description}
     * deletes (Baileys sends {@code delete: 'true'}); otherwise a new
     * description id is required. {@code previousDescId} is included as
     * {@code prev} only when non-null — Baileys reads it from metadata,
     * which wa-java callers must supply.
     */
    public static BinaryNode buildUpdateDescription(String iqId, String communityJid, String description,
                                                    String previousDescId, String newDescriptionId) {
        require(iqId, "iqId");
        require(communityJid, "communityJid");
        java.util.LinkedHashMap<String, String> attrs = new java.util.LinkedHashMap<>();
        if (description == null) {
            attrs.put("delete", "true");
        } else {
            require(newDescriptionId, "newDescriptionId");
            attrs.put("id", newDescriptionId);
        }
        if (previousDescId != null && !previousDescId.isEmpty()) attrs.put("prev", previousDescId);
        BinaryNode node;
        if (description == null) {
            node = new BinaryNode("description", attrs, null);
        } else {
            node = new BinaryNode("description", attrs,
                List.of(new BinaryNode("body", Map.of(), description)));
        }
        return iq(iqId, communityJid, "set", node);
    }

    /** Baileys {@code communitySettingUpdate}: the setting is a bare tag name (e.g. {@code locked}). */
    public static BinaryNode buildSettingUpdate(String iqId, String communityJid, String setting) {
        require(iqId, "iqId");
        require(communityJid, "communityJid");
        require(setting, "setting");
        return iq(iqId, communityJid, "set", BinaryNode.of(setting));
    }

    /** Baileys {@code communityMemberAddMode}: {@code <member_add_mode>} with the mode as text. */
    public static BinaryNode buildMemberAddMode(String iqId, String communityJid, String mode) {
        require(iqId, "iqId");
        require(communityJid, "communityJid");
        require(mode, "mode");
        return iq(iqId, communityJid, "set", new BinaryNode("member_add_mode", Map.of(), mode));
    }

    /** Baileys {@code communityJoinApprovalMode}: {@code <membership_approval_mode><community_join state="..."/>}. */
    public static BinaryNode buildJoinApprovalMode(String iqId, String communityJid, String mode) {
        require(iqId, "iqId");
        require(communityJid, "communityJid");
        require(mode, "mode");
        BinaryNode join = BinaryNode.of("community_join", Map.of("state", mode));
        return iq(iqId, communityJid, "set",
            new BinaryNode("membership_approval_mode", Map.of(), List.of(join)));
    }

    /**
     * Baileys {@code communityToggleEphemeral}: a null expiration sends
     * {@code <not_ephemeral/>}, otherwise {@code <ephemeral expiration="..."/>}.
     */
    public static BinaryNode buildToggleEphemeral(String iqId, String communityJid, Long ephemeralExpiration) {
        require(iqId, "iqId");
        require(communityJid, "communityJid");
        BinaryNode node = ephemeralExpiration == null
            ? BinaryNode.of("not_ephemeral")
            : BinaryNode.of("ephemeral", Map.of("expiration", String.valueOf(ephemeralExpiration)));
        return iq(iqId, communityJid, "set", node);
    }

    /** Baileys {@code communityInviteCode} (get) and {@code communityRevokeInvite} (set) share the bare {@code <invite/>} shape. */
    public static BinaryNode buildInviteCode(String iqId, String communityJid) {
        require(iqId, "iqId");
        require(communityJid, "communityJid");
        return iq(iqId, communityJid, "get", BinaryNode.of("invite"));
    }

    public static BinaryNode buildRevokeInvite(String iqId, String communityJid) {
        require(iqId, "iqId");
        require(communityJid, "communityJid");
        return iq(iqId, communityJid, "set", BinaryNode.of("invite"));
    }

    /** Baileys {@code communityAcceptInvite}: set to {@code @g.us} with {@code <invite code="..."/>}. */
    public static BinaryNode buildAcceptInvite(String iqId, String code) {
        require(iqId, "iqId");
        require(code, "code");
        return iq(iqId, Jid.SERVER_GROUP, "set", BinaryNode.of("invite", Map.of("code", code)));
    }

    /** Baileys {@code communityGetInviteInfo}: get to {@code @g.us} with {@code <invite code="..."/>}. */
    public static BinaryNode buildGetInviteInfo(String iqId, String code) {
        require(iqId, "iqId");
        require(code, "code");
        return iq(iqId, Jid.SERVER_GROUP, "get", BinaryNode.of("invite", Map.of("code", code)));
    }

    /**
     * Baileys {@code communityRevokeInviteV4}: set to the community
     * with {@code <revoke><participant jid="..."/>}. Baileys returns
     * true for any non-null result; there is nothing to parse beyond
     * result presence.
     */
    public static BinaryNode buildRevokeInviteV4(String iqId, String communityJid, String invitedJid) {
        require(iqId, "iqId");
        require(communityJid, "communityJid");
        require(invitedJid, "invitedJid");
        BinaryNode participant = BinaryNode.of("participant", Map.of("jid", invitedJid));
        return iq(iqId, communityJid, "set", new BinaryNode("revoke", Map.of(), List.of(participant)));
    }

    /**
     * Baileys {@code communityAcceptInviteV4}'s invite rewrite, pure
     * form ({@code lib/Socket/communities.js:347}): Baileys copies the
     * invite message with {@code proto.Message.GroupInviteMessage.fromObject}
     * and then sets only {@code inviteExpiration = 0} and
     * {@code inviteCode = ''} — every other field (group JID, group
     * name, thumbnail, caption, context info, group type) is preserved
     * verbatim. The caller emits it as
     * {@code messages.update: {key, update: {message: {groupInviteMessage}}}}
     * (see {@code JaWaClient.acceptCommunityInviteV4}); this method
     * builds the expired copy only and performs no I/O.
     *
     * @param invite the invite message to expire; must not be null
     * @return a copy with expiration 0 and an empty invite code
     */
    public static id.ja_wa.proto.Wa.Message.GroupInviteMessage expireInviteMessage(
            id.ja_wa.proto.Wa.Message.GroupInviteMessage invite) {
        if (invite == null) throw new IllegalArgumentException("invite must not be null");
        return id.ja_wa.proto.Wa.Message.GroupInviteMessage.newBuilder(invite)
            .setInviteExpiration(0L)
            .setInviteCode("")
            .build();
    }

    /**
     * Baileys {@code communityAcceptInviteV4}: set to the invite's
     * group JID with {@code <accept code expiration admin/>}, where
     * {@code admin} is the inviter's JID (Baileys passes the message
     * key's remote JID). Baileys additionally rewrites the invite
     * message to expired and emits {@code messages.update}; see
     * {@link #expireInviteMessage} and
     * {@code JaWaClient.acceptCommunityInviteV4} for that side-effect.
     */
    public static BinaryNode buildAcceptInviteV4(String iqId, String groupJid, String code,
                                                 long expiration, String adminJid) {
        require(iqId, "iqId");
        require(groupJid, "groupJid");
        require(code, "code");
        require(adminJid, "adminJid");
        java.util.LinkedHashMap<String, String> attrs = new java.util.LinkedHashMap<>();
        attrs.put("code", code);
        attrs.put("expiration", String.valueOf(expiration));
        attrs.put("admin", adminJid);
        return iq(iqId, groupJid, "set", new BinaryNode("accept", attrs, null));
    }

    /**
     * Baileys {@code communityParticipantsUpdate}. A {@code remove} action
     * carries {@code linked_groups: 'true'}; other actions carry no attrs.
     */
    public static BinaryNode buildParticipantsUpdate(String iqId, String communityJid,
                                                     List<String> participants, String action) {
        require(iqId, "iqId");
        require(communityJid, "communityJid");
        require(action, "action");
        List<BinaryNode> children = new ArrayList<>();
        if (participants != null) {
            for (String jid : participants) children.add(BinaryNode.of("participant", Map.of("jid", jid)));
        }
        Map<String, String> attrs = "remove".equals(action) ? Map.of("linked_groups", "true") : Map.of();
        return iq(iqId, communityJid, "set", new BinaryNode(action, attrs, children));
    }

    /** Baileys {@code communityRequestParticipantsList}. */
    public static BinaryNode buildRequestParticipantsList(String iqId, String communityJid) {
        require(iqId, "iqId");
        require(communityJid, "communityJid");
        return iq(iqId, communityJid, "get", BinaryNode.of("membership_approval_requests"));
    }

    /**
     * Baileys {@code communityRequestParticipantsUpdate}: the action tag
     * (e.g. {@code approve}/{@code reject}) wraps the participants. Baileys
     * passes the action string through, so callers supply it verbatim.
     */
    public static BinaryNode buildRequestParticipantsUpdate(String iqId, String communityJid,
                                                            List<String> participants, String action) {
        require(iqId, "iqId");
        require(communityJid, "communityJid");
        require(action, "action");
        List<BinaryNode> children = new ArrayList<>();
        if (participants != null) {
            for (String jid : participants) children.add(BinaryNode.of("participant", Map.of("jid", jid)));
        }
        BinaryNode actionNode = new BinaryNode(action, Map.of(), children);
        BinaryNode wrapper = new BinaryNode("membership_requests_action", Map.of(), List.of(actionNode));
        return iq(iqId, communityJid, "set", wrapper);
    }

    /**
     * Parses a {@code <sub_groups>} response into subgroup rows, following
     * Baileys {@code communityFetchLinkedGroups}: {@code id} is encoded as
     * {@code <id>@g.us}, {@code creator} is the owner, {@code size} and
     * {@code creation} are numbers when parseable. Rows without an id are
     * skipped. Returns an empty list for a null response or no
     * {@code <sub_groups>} child.
     */
    public static List<LinkedGroup> parseLinkedGroups(BinaryNode result) {
        List<LinkedGroup> out = new ArrayList<>();
        if (result == null) return out;
        BinaryNode subGroups = result.child("sub_groups");
        if (subGroups == null) return out;
        for (BinaryNode group : subGroups.children("group")) {
            String idAttr = group.attr("id");
            if (idAttr == null || idAttr.isEmpty()) continue;
            String id = idAttr.contains("@") ? idAttr : idAttr + "@" + Jid.SERVER_GROUP;
            out.add(new LinkedGroup(id, group.attr("subject"),
                parseLongOrNull(group.attr("creation")),
                group.attr("creator"),
                parseLongOrNull(group.attr("size"))));
        }
        return out;
    }

    /**
     * Parses a participants-action response ({@code <action>} node wrapping
     * {@code <participant jid error?>} rows) into {@link ParticipantResult}s,
     * defaulting a missing error to {@code 200} exactly as Baileys does
     * ({@code p.attrs.error || '200'}). Returns an empty list when the
     * action node is absent.
     */
    public static List<ParticipantResult> parseParticipantsResult(BinaryNode result, String action) {
        List<ParticipantResult> out = new ArrayList<>();
        if (result == null || action == null) return out;
        BinaryNode node = result.child(action);
        if (node == null) return out;
        for (BinaryNode p : node.children("participant")) {
            String jid = p.attr("jid");
            if (jid == null) continue;
            String error = p.attr("error");
            out.add(new ParticipantResult(jid, error == null ? "200" : error));
        }
        return out;
    }

    /** Extracts the invite {@code code} attr from an invite response, or null when absent. */
    public static String parseInviteCode(BinaryNode result) {
        if (result == null) return null;
        BinaryNode invite = result.child("invite");
        return invite == null ? null : invite.attr("code");
    }

    /** Extracts the community {@code jid} attr from an accept-invite response, or null when absent. */
    public static String parseAcceptInviteCommunityJid(BinaryNode result) {
        if (result == null) return null;
        BinaryNode community = result.child("community");
        return community == null ? null : community.attr("jid");
    }

    private static BinaryNode iq(String iqId, String to, String type, BinaryNode payload) {
        return new BinaryNode("iq",
            Map.of("id", iqId, "type", type, "xmlns", "w:g2", "to", to),
            List.of(payload));
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }

    private static Long parseLongOrNull(String raw) {
        if (raw == null) return null;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
