package id.ja_wa.feature.newsletter;

import id.ja_wa.domain.model.NewsletterMessage;
import id.ja_wa.proto.Wa;
import id.ja_wa.protocol.codec.BinaryNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure decoder for newsletter messages that arrive with a {@code <plaintext>}
 * child carrying a serialized {@code Wa.Message}.
 *
 * <p>Baileys source: {@code newsletterFetchMessages} in
 * {@code lib/Socket/newsletter.js} — the fetch response is
 * {@code iq -> messages(jid) -> message[]} and each {@code message} child is
 * decoded only when it has a {@code <plaintext>} child; messages without
 * one, and messages whose plaintext fails to decode, are skipped (Baileys
 * logs and continues). This class mirrors that behaviour exactly: no
 * exception is thrown for a missing or undecodable plaintext, the message
 * is simply skipped.
 *
 * <p>What this does <em>not</em> cover, and why: newsletter messages do not
 * go through the Signal decryption path in
 * {@code id.ja_wa.feature.messaging.MessageReceiver} (which decrypts
 * {@code <enc>} children with {@code pkmsg}/{@code msg}/{@code skmsg}
 * sessions). Newsletter fetch payloads are already plaintext protobuf, so
 * there is no Signal session to decrypt and nothing in MessageReceiver to
 * reuse here. Live inbound newsletter <em>notifications</em> (reactions,
 * settings, participant changes) are a different stanza shape handled by
 * the notification parsers, not by plaintext decoding, and are out of scope
 * for this decoder.
 */
public final class NewsletterMessageDecoder {

    private NewsletterMessageDecoder() {}

    /**
     * Decodes one {@code <message>} node from a newsletter fetch response,
     * or {@code null} when it has no {@code <plaintext>} child or the
     * plaintext does not parse as a {@code Wa.Message} (mirrors Baileys'
     * skip-on-failure).
     *
     * @param messageNode a {@code <message>} child of a {@code <messages>} node
     * @param newsletterJid newsletter JID, used only for documentation of the
     *                    source; the returned record does not carry it because
     *                    {@link NewsletterMessage} has no JID field
     * @return decoded message, or null when skipped
     */
    public static NewsletterMessage decode(BinaryNode messageNode, String newsletterJid) {
        if (messageNode == null) return null;
        BinaryNode plaintext = messageNode.child("plaintext");
        if (plaintext == null || plaintext.bytesContent() == null) return null;
        Wa.Message protoMsg;
        try {
            protoMsg = Wa.Message.parseFrom(plaintext.bytesContent());
        } catch (Exception ignored) {
            return null;
        }
        long serverId = parseLong(messageNode.attr("server_id"));
        long timestamp = parseLong(messageNode.attr("t"));
        int viewsCount = 0;
        BinaryNode viewsNode = messageNode.child("views_count");
        if (viewsNode != null) {
            viewsCount = (int) parseLong(viewsNode.attr("count"));
        }
        Map<String, Integer> reactions = new HashMap<>();
        BinaryNode reactsNode = messageNode.child("reactions");
        if (reactsNode != null) {
            for (BinaryNode r : reactsNode.children("reaction")) {
                String code = r.attr("code");
                if (code != null) reactions.put(code, (int) parseLong(r.attr("count")));
            }
        }
        return new NewsletterMessage(serverId, messageNode.attr("id"), messageNode.attr("type"),
            timestamp, viewsCount, reactions, protoMsg);
    }

    /**
     * Decodes all {@code <message>} children of a {@code <messages>} node,
     * skipping messages without decodable plaintext. Returns an empty list
     * for a null node or a node with no message children.
     */
    public static List<NewsletterMessage> decodeAll(BinaryNode messagesNode, String newsletterJid) {
        List<NewsletterMessage> out = new ArrayList<>();
        if (messagesNode == null) return out;
        for (BinaryNode child : messagesNode.children("message")) {
            NewsletterMessage decoded = decode(child, newsletterJid);
            if (decoded != null) out.add(decoded);
        }
        return out;
    }

    private static long parseLong(String raw) {
        if (raw == null) return 0L;
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
