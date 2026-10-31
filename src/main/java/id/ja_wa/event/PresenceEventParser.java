package id.ja_wa.event;

import id.ja_wa.protocol.codec.BinaryNode;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Parses inbound {@code presence} / chat-presence stanzas into
 * {@link PresenceUpdate} following Baileys {@code handlePresenceUpdate}
 * (lib/Socket/chats.js) exactly, except that the Baileys
 * {@code shouldIgnoreJid} gate is not replicated: JaWaClient decides which
 * stanzas reach this parser, and this parser reports what it is given.
 * <p>
 * Rules:
 * <ul>
 *   <li>{@code jid = attrs.from}, {@code participant = attrs.participant}
 *       or {@code attrs.from} when the former is absent.</li>
 *   <li>Tag {@code presence}: {@code lastKnownPresence} is
 *       {@code unavailable} when {@code attrs.type == "unavailable"},
 *       otherwise {@code available}; {@code lastSeen} is
 *       {@code attrs.last} parsed as a long, unless it is absent or
 *       {@code "deny"}; {@code groupOnlineCount} is {@code attrs.count}
 *       parsed as an int when present.</li>
 *   <li>Otherwise, when the stanza content is a list of children: the first
 *       child's tag is the presence, except {@code paused} maps to
 *       {@code available} and a child with {@code media == "audio"} maps
 *       to {@code recording}.</li>
 *   <li>Neither shape: Baileys logs an invalid-presence error; this parser
 *       logs via {@link java.util.logging} and returns {@code null}.</li>
 * </ul>
 */
public final class PresenceEventParser {

    private static final Logger LOG = Logger.getLogger(PresenceEventParser.class.getName());

    private PresenceEventParser() {}

    /**
     * Parses a presence stanza.
     *
     * @param node inbound stanza node
     * @return the Baileys-shaped {@link PresenceUpdate}, or {@code null}
     *         when the node is not a recognisable presence stanza
     */
    public static PresenceUpdate parse(BinaryNode node) {
        if (node == null) return null;
        String jid = node.attr("from");
        if (jid == null) {
            LOG.log(Level.WARNING, "Invalid presence node without from: {0}", node);
            return null;
        }
        String participant = node.attr("participant");
        if (participant == null) participant = jid;

        PresenceInfo presence = null;
        if ("presence".equals(node.tag())) {
            String type = node.attr("type");
            String lastKnownPresence = "unavailable".equals(type) ? "unavailable" : "available";
            Long lastSeen = null;
            String last = node.attr("last");
            if (last != null && !"deny".equals(last)) {
                try {
                    lastSeen = Long.parseLong(last);
                } catch (NumberFormatException ignored) {
                    // Baileys +attrs.last would yield NaN; null is the Java equivalent of "no usable value".
                }
            }
            Integer groupOnlineCount = null;
            String count = node.attr("count");
            if (count != null) {
                try {
                    groupOnlineCount = Integer.parseInt(count);
                } catch (NumberFormatException ignored) {
                }
            }
            presence = new PresenceInfo(lastKnownPresence, lastSeen, groupOnlineCount);
        } else if (node.content() instanceof List<?> children && !children.isEmpty()
                && children.get(0) instanceof BinaryNode firstChild) {
            String type = firstChild.tag();
            if ("paused".equals(type)) {
                type = "available";
            }
            if ("audio".equals(firstChild.attr("media"))) {
                type = "recording";
            }
            presence = new PresenceInfo(type, null, null);
        } else {
            LOG.log(Level.WARNING, "Invalid presence node: {0}", node);
            return null;
        }
        return new PresenceUpdate(jid, Map.of(participant, presence));
    }
}
