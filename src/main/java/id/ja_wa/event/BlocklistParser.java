package id.ja_wa.event;

import id.ja_wa.protocol.codec.BinaryNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses Baileys {@code account_sync} blocklist notifications into
 * {@code blocklist.update} events, one per item.
 * <p>
 * Source: Baileys {@code processNotification} case {@code account_sync} in
 * {@code lib/Socket/messages-recv.js}: for each {@code <item>} child of the
 * {@code <blocklist>} child, one event with a single-element blocklist and
 * type {@code add} for action {@code block}, {@code remove} otherwise.
 */
public final class BlocklistParser {

    private BlocklistParser() {}

    /**
     * Parses an account_sync notification stanza.
     *
     * @param node notification node; nodes without a {@code blocklist}
     *             child yield an empty list
     * @return one blocklist update per item, in stanza order
     */
    public static List<BlocklistUpdate> parse(BinaryNode node) {
        List<BlocklistUpdate> out = new ArrayList<>();
        if (node == null) return out;
        BinaryNode blocklist = node.child("blocklist");
        if (blocklist == null) return out;
        for (BinaryNode item : blocklist.children("item")) {
            String jid = item.attr("jid");
            if (jid == null) continue;
            out.add(new BlocklistUpdate(List.of(jid),
                "block".equals(item.attr("action")) ? "add" : "remove"));
        }
        return out;
    }
}
