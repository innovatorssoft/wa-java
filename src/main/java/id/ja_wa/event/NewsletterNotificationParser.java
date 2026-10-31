package id.ja_wa.event;

import id.ja_wa.protocol.codec.BinaryNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses Baileys {@code newsletter} notifications into Baileys events.
 * <p>
 * Source: Baileys {@code handleNewsletterNotification} in
 * {@code lib/Socket/messages-recv.js}: one event per child tag of a
 * {@code <notification type="newsletter" from="..." participant="...">}
 * stanza. A {@code message} child (newsletter plaintext) is deliberately
 * not converted here: Baileys decodes it into a full message and upserts
 * it, but wa-java's newsletter path carries no sender/key context for such
 * a message, so inventing one would misrepresent it.
 */
public final class NewsletterNotificationParser {

    private NewsletterNotificationParser() {}

    /**
     * Parses a newsletter notification stanza.
     *
     * @param node notification node; null yields an empty list
     * @return events to dispatch, one per recognised child, in stanza order
     */
    public static List<EventDispatch> parse(BinaryNode node) {
        List<EventDispatch> out = new ArrayList<>();
        if (node == null) return out;
        String from = node.attr("from");
        if (from == null) return out;
        String author = node.attr("participant");
        for (BinaryNode child : node.childrenList()) {
            switch (child.tag()) {
                case "reaction" -> {
                    BinaryNode codeNode = child.child("reaction");
                    String code = codeNode == null ? null : codeNode.textContent();
                    out.add(new EventDispatch(BaileysEventType.NEWSLETTER_REACTION,
                        new NewsletterReaction(from, child.attr("message_id"),
                            code == null ? "" : code, 1)));
                }
                case "view" -> {
                    long count = 0;
                    String text = child.textContent();
                    if (text != null) {
                        try {
                            count = Long.parseLong(text.trim());
                        } catch (NumberFormatException ignored) {}
                    }
                    out.add(new EventDispatch(BaileysEventType.NEWSLETTER_VIEW,
                        new NewsletterView(from, child.attr("message_id"), count)));
                }
                case "participant" -> out.add(new EventDispatch(
                    BaileysEventType.NEWSLETTER_PARTICIPANTS_UPDATE,
                    new NewsletterParticipantsUpdate(from, author, child.attr("jid"),
                        child.attr("action"), child.attr("role"))));
                case "update" -> {
                    BinaryNode settings = child.child("settings");
                    if (settings == null) break;
                    BinaryNode nameNode = settings.child("name");
                    BinaryNode descNode = settings.child("description");
                    String name = nameNode == null ? null : nameNode.textContent();
                    String description = descNode == null ? null : descNode.textContent();
                    out.add(new EventDispatch(BaileysEventType.NEWSLETTER_SETTINGS_UPDATE,
                        new NewsletterSettingsUpdate(from, name, description)));
                }
                default -> {
                    // message and unknown children: no newsletter event here.
                }
            }
        }
        return out;
    }
}
