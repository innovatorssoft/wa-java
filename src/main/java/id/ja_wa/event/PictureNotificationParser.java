package id.ja_wa.event;

import id.ja_wa.protocol.codec.BinaryNode;

import java.util.List;

/**
 * Parses Baileys {@code picture} notifications into
 * {@code contacts.update}.
 * <p>
 * Source: Baileys {@code processNotification} case {@code picture} in
 * {@code lib/Socket/messages-recv.js}: a {@code set} child means the picture
 * changed, a {@code delete} child means it was removed; the contact id is
 * the normalised sender JID, falling back to the child's {@code hash}
 * attribute. wa-java carries the sender JID verbatim (it has no
 * JID-normalisation equivalent of Baileys' {@code jidNormalizedUser} for
 * this path beyond {@code Jid.bare}, which callers may apply).
 */
public final class PictureNotificationParser {

    private PictureNotificationParser() {}

    /**
     * Parses a picture notification stanza.
     *
     * @param node notification node
     * @return contacts update, or null when the node carries neither a
     *         {@code set} nor a {@code delete} child
     */
    public static ContactsUpdate parse(BinaryNode node) {
        if (node == null) return null;
        BinaryNode set = node.child("set");
        BinaryNode del = node.child("delete");
        if (set == null && del == null) return null;
        String id = node.attr("from");
        if (id == null) {
            BinaryNode hashNode = set != null ? set : del;
            id = hashNode == null ? null : hashNode.attr("hash");
        }
        if (id == null) id = "";
        return new ContactsUpdate(List.of(new ContactsUpdate.ContactUpdate(
            id, null, null, set != null ? "changed" : "removed")));
    }
}
