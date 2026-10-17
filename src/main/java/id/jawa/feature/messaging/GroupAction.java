package id.jawa.feature.messaging;


import id.jawa.protocol.codec.BinaryNode;
import id.jawa.domain.model.Jid;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class GroupAction {

    private GroupAction() {}

    public enum ParticipantChange {
        ADD("add"),
        REMOVE("remove"),
        PROMOTE("promote"),
        DEMOTE("demote");

        final String tag;

        ParticipantChange(String tag) { this.tag = tag; }
    }

    public static BinaryNode buildCreate(String iqId, String name, List<String> participantBareJids,
                                         String createKey) {
        List<BinaryNode> children = new ArrayList<>();
        for (String jid : participantBareJids) {
            children.add(BinaryNode.of("participant", Map.of("jid", jid)));
        }
        BinaryNode create = new BinaryNode("create",
            Map.of("subject", name, "key", createKey),
            children);
        return iq(iqId, Jid.SERVER_GROUP, create);
    }

    public static BinaryNode buildLeave(String iqId, String groupJid) {
        BinaryNode group = BinaryNode.of("group", Map.of("id", groupJid));
        BinaryNode leave = new BinaryNode("leave", Map.of(), List.of(group));
        return iq(iqId, Jid.SERVER_GROUP, leave);
    }

    public static BinaryNode buildParticipantChange(String iqId, String groupJid,
                                                    ParticipantChange action,
                                                    List<String> participantBareJids) {
        List<BinaryNode> children = new ArrayList<>();
        for (String jid : participantBareJids) {
            children.add(BinaryNode.of("participant", Map.of("jid", jid)));
        }
        BinaryNode actionNode = new BinaryNode(action.tag, Map.of(), children);
        return iq(iqId, groupJid, actionNode);
    }

    public static BinaryNode buildSetSubject(String iqId, String groupJid, String subject) {
        BinaryNode subj = new BinaryNode("subject", Map.of(), subject);
        return iq(iqId, groupJid, subj);
    }

    public static BinaryNode buildSetDescription(String iqId, String groupJid,
                                                  String body, String previousId, String newId) {
        LinkedHashMap<String, String> attrs = new LinkedHashMap<>();
        attrs.put("id", newId);
        if (previousId != null && !previousId.isEmpty()) attrs.put("prev", previousId);
        BinaryNode description;
        if (body == null || body.isEmpty()) {
            attrs.put("delete", "true");
            description = new BinaryNode("description", attrs, null);
        } else {
            BinaryNode bodyNode = new BinaryNode("body", Map.of(), body);
            description = new BinaryNode("description", attrs, List.of(bodyNode));
        }
        return iq(iqId, groupJid, description);
    }

    public static BinaryNode buildInviteLink(String iqId, String groupJid, boolean reset) {
        return new BinaryNode("iq",
            Map.of(
                "id",    iqId,
                "type",  reset ? "set" : "get",
                "xmlns", "w:g2",
                "to",    groupJid
            ),
            List.of(new BinaryNode("invite", Map.of(), null)));
    }

    public static BinaryNode buildLinkInfo(String iqId, String code) {
        return new BinaryNode("iq",
            Map.of(
                "id",    iqId,
                "type",  "get",
                "xmlns", "w:g2",
                "to",    Jid.SERVER_GROUP
            ),
            List.of(new BinaryNode("invite", Map.of("code", code), null)));
    }

    public static BinaryNode buildJoinWithLink(String iqId, String code) {
        return new BinaryNode("iq",
            Map.of(
                "id",    iqId,
                "type",  "set",
                "xmlns", "w:g2",
                "to",    Jid.SERVER_GROUP
            ),
            List.of(new BinaryNode("invite", Map.of("code", code), null)));
    }

    public static BinaryNode buildSetLocked(String iqId, String groupJid, boolean locked) {
        BinaryNode node = new BinaryNode(locked ? "locked" : "unlocked", Map.of(), null);
        return iq(iqId, groupJid, node);
    }

    public static BinaryNode buildSetAnnounce(String iqId, String groupJid, boolean announce) {
        BinaryNode node = new BinaryNode(announce ? "announcement" : "not_announcement", Map.of(), null);
        return iq(iqId, groupJid, node);
    }

    private static BinaryNode iq(String iqId, String toJid, BinaryNode payload) {
        return new BinaryNode("iq",
            Map.of(
                "id",    iqId,
                "type",  "set",
                "xmlns", "w:g2",
                "to",    toJid
            ),
            List.of(payload));
    }
}
