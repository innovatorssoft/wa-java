package id.jawa.feature.messaging;

import id.jawa.protocol.codec.BinaryNode;

import java.util.List;
import java.util.Map;

/**
 * Pure builder and response parser for Baileys' {@code createCallLink}
 * ({@code lib/Socket/chats.js}).
 *
 * <p>Baileys sends a {@code <call>} stanza (not an {@code iq}) to
 * {@code @call} with a {@code <link_create media="...">} child, plus
 * an {@code <event start_time="...">} child when an event time is
 * given, and returns the created link's token from the
 * {@code <link_create>} child of the response. Builders perform no
 * I/O, and the transport is a parent hook: wa-java's
 * {@code sendIqAsync} only sends {@code iq} stanzas, so a
 * {@code <call>} stanza needs its own send path before this builder
 * can be executed live.
 */
public final class CallLinkQuery {

    private CallLinkQuery() {}

    /**
     * Builds the {@code createCallLink} stanza.
     *
     * @param stanzaId stanza id assigned by the caller (Baileys uses
     *         {@code generateMessageTag})
     * @param mediaType media type, e.g. {@code video} or {@code audio}
     * @param eventStartTime event start time in epoch seconds, or null
     *         for a link with no event
     * @return {@code <call>} node to send
     */
    public static BinaryNode buildCreate(String stanzaId, String mediaType, Long eventStartTime) {
        if (stanzaId == null || stanzaId.isBlank()) {
            throw new IllegalArgumentException("stanzaId must not be blank");
        }
        if (mediaType == null || mediaType.isBlank()) {
            throw new IllegalArgumentException("mediaType must not be blank");
        }
        List<BinaryNode> linkChildren = null;
        if (eventStartTime != null) {
            linkChildren = List.of(new BinaryNode("event",
                Map.of("start_time", String.valueOf(eventStartTime)), null));
        }
        BinaryNode linkCreate = new BinaryNode("link_create", Map.of("media", mediaType), linkChildren);
        return new BinaryNode("call",
            Map.of("id", stanzaId, "to", "@call"),
            List.of(linkCreate));
    }

    /**
     * Extracts the created link token from a {@code createCallLink}
     * response (the {@code token} attr of its {@code <link_create>}
     * child), or null when absent.
     */
    public static String parseToken(BinaryNode result) {
        if (result == null) return null;
        BinaryNode linkCreate = result.child("link_create");
        return linkCreate == null ? null : linkCreate.attr("token");
    }
}
