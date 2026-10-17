package id.jawa.domain.model;

import id.jawa.protocol.codec.BinaryNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Message delivery, read, or playback acknowledgment receipt received from WhatsApp servers.
 *
 * @param type The receipt classification string (null for delivery, "read", "played", "server")
 * @param chatJid Originating conversation JID
 * @param senderJid Message participant JID in group context
 * @param recipientJid Destination recipient JID if specified
 * @param msgIds List of message IDs acknowledged by this receipt
 * @param timestamp Server epoch timestamp seconds
 */
public record Receipt(
    String type,
    String chatJid,
    String senderJid,
    String recipientJid,
    List<String> msgIds,
    long timestamp
) {

    /**
     * Parses an incoming stanza node into a structured Receipt instance.
     *
     * @param node The raw BinaryNode representing a receipt stanza
     * @return Parsed Receipt object, or null if essential attributes are absent
     */
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
