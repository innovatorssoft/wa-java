package id.jawa.feature.messaging;

import id.jawa.domain.model.Jid;
import id.jawa.protocol.codec.BinaryNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class GroupListQuery {

    private GroupListQuery() {}

    public record GroupInfo(
        String jid,
        String subject,
        long creationTimestamp,
        long subjectTimestamp,
        String owner,
        List<String> participantJids,
        boolean isEphemeral,
        int ephemeralExpiration
    ) {
        public GroupInfo(String jid, String subject, long creationTimestamp, long subjectTimestamp, String owner, List<String> participantJids) {
            this(jid, subject, creationTimestamp, subjectTimestamp, owner, participantJids, false, 0);
        }
    }

    public static BinaryNode buildQuery(String iqId) {
        BinaryNode participating = new BinaryNode("participating", Map.of(),
            List.of(
                new BinaryNode("participants", Map.of(), null),
                new BinaryNode("description",  Map.of(), null)
            ));
        return new BinaryNode("iq",
            Map.of(
                "to",    Jid.SERVER_GROUP,
                "xmlns", "w:g2",
                "type",  "get",
                "id",    iqId
            ),
            List.of(participating));
    }

    public static BinaryNode buildGroupInfoQuery(String iqId, String groupJid) {
        BinaryNode query = new BinaryNode("query", Map.of("request", "interactive"), null);
        return new BinaryNode("iq",
            Map.of(
                "to",    groupJid,
                "xmlns", "w:g2",
                "type",  "get",
                "id",    iqId
            ),
            List.of(query));
    }

    public static GroupInfo parseSingleGroup(BinaryNode g) {
        if (g == null) return null;
        String groupId = g.attr("id");
        String creator = g.attr("creator");
        String subject = g.attr("subject", "");
        long creation = parseLong(g.attr("creation"), 0);
        long subjectTs = parseLong(g.attr("s_t"), 0);
        String groupJid = g.attr("jid");
        if (groupJid == null || groupJid.isEmpty()) {
            groupJid = (groupId != null ? groupId : "") + "@" + Jid.SERVER_GROUP;
        }
        List<String> participants = new ArrayList<>();
        for (BinaryNode p : g.childrenList()) {
            if (!"participant".equals(p.tag())) continue;
            String pj = p.attr("jid");
            String pnJid = p.attr("pn_jid");
            String lid = p.attr("lid");
            String phone = p.attr("phone_number");

            if (pj != null && pj.endsWith("@lid") && pnJid != null && pnJid.endsWith("@s.whatsapp.net")) {
                id.jawa.client.JaWaClient.LID_TO_PN_MAP.put(pj, pnJid);
            } else if (pj != null && pj.endsWith("@s.whatsapp.net") && pnJid != null && pnJid.endsWith("@lid")) {
                id.jawa.client.JaWaClient.LID_TO_PN_MAP.put(pnJid, pj);
            }
            if (lid != null && lid.endsWith("@lid") && pj != null && pj.endsWith("@s.whatsapp.net")) {
                id.jawa.client.JaWaClient.LID_TO_PN_MAP.put(lid, pj);
            }
            if (pj != null && pj.endsWith("@lid") && phone != null && !phone.isBlank()) {
                String digits = phone.replaceAll("[^0-9]", "");
                if (!digits.isEmpty()) {
                    id.jawa.client.JaWaClient.LID_TO_PN_MAP.put(pj, digits + "@s.whatsapp.net");
                }
            }

            if (pj != null) participants.add(pj);
        }
        String creatorPn = g.attr("creator_pn");
        if (creator != null && creator.endsWith("@lid") && creatorPn != null && creatorPn.endsWith("@s.whatsapp.net")) {
            id.jawa.client.JaWaClient.LID_TO_PN_MAP.put(creator, creatorPn);
        }
        boolean isEphemeral = false;
        int ephemeralExpiration = 0;
        BinaryNode ephNode = g.child("ephemeral");
        if (ephNode != null) {
            isEphemeral = true;
            ephemeralExpiration = (int) parseLong(ephNode.attr("expiration"), 0);
        }
        return new GroupInfo(groupJid, subject, creation, subjectTs, creator, participants, isEphemeral, ephemeralExpiration);
    }

    public static List<GroupInfo> parseResponse(BinaryNode iqResponse) {
        List<GroupInfo> out = new ArrayList<>();
        BinaryNode groups = iqResponse.child("groups");
        if (groups == null) {
            BinaryNode single = iqResponse.child("group");
            if (single != null) {
                GroupInfo info = parseSingleGroup(single);
                if (info != null) out.add(info);
            }
            return out;
        }
        for (BinaryNode g : groups.childrenList()) {
            if (!"group".equals(g.tag())) continue;
            GroupInfo info = parseSingleGroup(g);
            if (info != null) out.add(info);
        }
        return out;
    }

    private static long parseLong(String s, long fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return fallback; }
    }
}
