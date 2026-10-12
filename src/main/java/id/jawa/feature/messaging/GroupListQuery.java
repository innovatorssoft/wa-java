package id.jawa.feature.messaging;

import id.jawa.protocol.connection.*;
import id.jawa.protocol.codec.*;
import id.jawa.protocol.crypto.*;
import id.jawa.domain.model.*;
import id.jawa.domain.store.*;
import id.jawa.feature.pairing.*;
import id.jawa.feature.messaging.*;
import id.jawa.feature.media.*;
import id.jawa.feature.appstate.*;
import id.jawa.feature.signal.*;

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
                new BinaryNode("description",  Map.of(), null),
                new BinaryNode("ephemeral",    Map.of(), null)
            ));
        return new BinaryNode("iq",
            Map.of(
                "to",    "@" + id.jawa.domain.model.Jid.SERVER_GROUP,
                "xmlns", "w:g2",
                "type",  "get",
                "id",    iqId
            ),
            List.of(participating));
    }

    public static List<GroupInfo> parseResponse(BinaryNode iqResponse) {
        List<GroupInfo> out = new ArrayList<>();
        BinaryNode groups = iqResponse.child("groups");
        if (groups == null) return out;
        for (BinaryNode g : groups.childrenList()) {
            if (!"group".equals(g.tag())) continue;
            String groupId = g.attr("id");
            String creator = g.attr("creator");
            String subject = g.attr("subject", "");
            long creation = parseLong(g.attr("creation"), 0);
            long subjectTs = parseLong(g.attr("s_t"), 0);
            String groupJid = (groupId != null ? groupId : "") + "@" + id.jawa.domain.model.Jid.SERVER_GROUP;
            List<String> participants = new ArrayList<>();
            for (BinaryNode p : g.childrenList()) {
                if (!"participant".equals(p.tag())) continue;
                String pj = p.attr("jid");
                if (pj != null) participants.add(pj);
            }
            boolean isEphemeral = false;
            int ephemeralExpiration = 0;
            BinaryNode ephNode = g.child("ephemeral");
            if (ephNode != null) {
                isEphemeral = true;
                ephemeralExpiration = (int) parseLong(ephNode.attr("expiration"), 0);
            }
            out.add(new GroupInfo(groupJid, subject, creation, subjectTs, creator, participants, isEphemeral, ephemeralExpiration));
        }
        return out;
    }

    private static long parseLong(String s, long fallback) {
        if (s == null || s.isEmpty()) return fallback;
        try { return Long.parseLong(s); } catch (NumberFormatException e) { return fallback; }
    }
}
