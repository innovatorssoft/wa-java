package id.jawa.event;

import id.jawa.protocol.codec.BinaryNode;

import java.util.List;

/**
 * Parses Baileys {@code mediaretry} notifications into
 * {@code messages.media-update}.
 * <p>
 * Source: Baileys {@code decodeMediaRetryNode} in
 * {@code lib/Utils/messages-media.js}: the key comes from the stanza id and
 * the {@code rmr} child's {@code jid}/{@code from_me}/{@code participant}
 * attributes; an {@code error} child contributes its numeric code.
 * Increment 9: the encrypted media ({@code ciphertext}/{@code iv} from
 * the {@code encrypt} child's {@code enc_p}/{@code enc_iv}) is now
 * carried on the success path too, as Baileys models it — the
 * Increment 1 omission is closed so {@code updateMediaMessage} can
 * run Baileys' {@code decryptMediaRetryData} step. When the
 * notification carries no {@code encrypt} child (the error path),
 * both stay null, exactly as before.
 */
public final class MediaRetryParser {

    private MediaRetryParser() {}

    /**
     * Parses a mediaretry notification stanza.
     *
     * @param node notification node
     * @return media-update payload, or null when the node has no
     *         {@code rmr} child to key it by
     */
    public static MessagesMediaUpdate parse(BinaryNode node) {
        if (node == null) return null;
        BinaryNode rmr = node.child("rmr");
        if (rmr == null) return null;
        Integer errorCode = null;
        BinaryNode error = node.child("error");
        if (error != null && error.attr("code") != null) {
            try {
                errorCode = Integer.parseInt(error.attr("code"));
            } catch (NumberFormatException ignored) {}
        }
        byte[] ciphertext = null;
        byte[] iv = null;
        BinaryNode encrypt = node.child("encrypt");
        if (encrypt != null) {
            BinaryNode encP = encrypt.child("enc_p");
            BinaryNode encIv = encrypt.child("enc_iv");
            if (encP != null) ciphertext = encP.bytesContent();
            if (encIv != null) iv = encIv.bytesContent();
        }
        return new MessagesMediaUpdate(List.of(new MessagesMediaUpdate.MediaUpdate(
            node.attr("id"), rmr.attr("jid"), "true".equals(rmr.attr("from_me")),
            rmr.attr("participant"), errorCode, ciphertext, iv)));
    }
}
