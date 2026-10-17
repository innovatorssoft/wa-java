package id.jawa.domain.model;


import id.jawa.protocol.codec.BinaryNode;

import java.util.ArrayList;
import java.util.List;

public record Receipt(
    String type,
    String chatJid,
    String senderJid,
    String recipientJid,
    List<String> msgIds,
    long timestamp
) {

    public static Receipt parse(BinaryNode node) {
        String id = node.attr("id");
        String from = node.attr("from");
        if (id == null || from == null) return null;

        List<String> ids = new ArrayList<>();
        ids.add(id);
        BinaryNode list = node.child("list");
        if (list != null) {
            for (BinaryNode item : list.childrenList()) {
                if (!"item".equals(item.tag())) continue;
                String iid = item.attr("id");
                if (iid != null) ids.add(iid);
            }
        }

        long ts = 0L;
        String tAttr = node.attr("t");
        if (tAttr != null) {
            try { ts = Long.parseLong(tAttr); }
            catch (NumberFormatException ignored) {}
        }

        return new Receipt(
            node.attr("type"),
            from,
            node.attr("participant"),
            node.attr("recipient"),
            List.copyOf(ids),
            ts
        );
    }
}
