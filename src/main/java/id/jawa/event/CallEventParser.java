package id.jawa.event;

import id.jawa.protocol.codec.BinaryNode;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Parses inbound {@code call} stanzas into {@link CallEvent} following
 * Baileys {@code handleCall} (lib/Socket/messages-recv.js) and
 * {@code getCallStatusFromNode} (lib/Utils/generics.js).
 * <p>
 * Rules:
 * <ul>
 *   <li>The first child of the stanza is the info child; if absent, or if
 *       it carries no {@code call-id}, parsing fails (logged, {@code null}).</li>
 *   <li>Status: {@code offer}/{@code offer_notice} → {@code OFFER};
 *       {@code terminate} with {@code reason=timeout} → {@code TIMEOUT},
 *       otherwise {@code TERMINATE}; {@code preaccept} → {@code PREACCEPT};
 *       {@code transport} → {@code TRANSPORT};
 *       {@code relaylatency} → {@code RELAYLATENCY};
 *       {@code reject} → {@code REJECT}; {@code accept} → {@code ACCEPT};
 *       anything else → {@code RINGING}.</li>
 *   <li>{@code chatId = attrs.from} (stanza),
 *       {@code from = info.attrs.from} or {@code info.attrs.call-creator},
 *       {@code callerPn = info.attrs.caller_pn},
 *       {@code id = info.attrs.call-id},
 *       {@code date} = stanza {@code attrs.t} (epoch seconds; Baileys builds
 *       a JS {@code Date} as {@code t * 1000}, exposed here as
 *       {@link CallEvent#dateEpochMs()}),
 *       {@code offline} = stanza carries an {@code offline} attribute.</li>
 *   <li>For {@code RELAYLATENCY}, latency is read from the <em>info
 *       child's</em> {@code latency} / {@code latency_ms} /
 *       {@code latency-ms} attributes (in that order) and kept only when
 *       finite.</li>
 *   <li>On {@code OFFER}: {@code isVideo} = a {@code video} child is present,
 *       {@code isGroup} = info {@code type == "group"} or a
 *       {@code group-jid} attribute is present, {@code groupJid} = the
 *       {@code group-jid} attribute; the offer is cached in memory.</li>
 *   <li>For later events for the same call id, {@code isVideo},
 *       {@code isGroup} and {@code callerPn} are backfilled from the cached
 *       offer when absent on the event itself.</li>
 *   <li>On {@code REJECT}, {@code ACCEPT}, {@code TIMEOUT} or
 *       {@code TERMINATE} the cached offer is deleted (after backfill, so
 *       the terminal event itself still carries the offer's info).</li>
 * </ul>
 * <p>
 * The offer cache is in-memory and per parser instance (JaWaClient holds
 * one instance); it is not persisted.
 */
public final class CallEventParser {

    private static final Logger LOG = Logger.getLogger(CallEventParser.class.getName());

    private final ConcurrentHashMap<String, CallEvent> offerCache = new ConcurrentHashMap<>();

    /**
     * Parses a call stanza.
     *
     * @param node inbound {@code call} stanza
     * @return the {@link CallEvent}, or {@code null} when the info child or
     *         the call id is missing
     */
    public CallEvent parse(BinaryNode node) {
        if (node == null) return null;
        List<BinaryNode> children = node.childrenList();
        if (children.isEmpty()) {
            LOG.log(Level.WARNING, "Missing call info in call node: {0}", node);
            return null;
        }
        BinaryNode info = children.get(0);
        String callId = info.attr("call-id");
        if (callId == null || callId.isBlank()) {
            LOG.log(Level.WARNING, "Missing call-id in call node: {0}", node);
            return null;
        }

        CallStatus status = statusFromNode(info);

        String from = info.attr("from");
        if (from == null) from = info.attr("call-creator");
        String callerPn = info.attr("caller_pn");
        long dateEpochSec = 0L;
        String t = node.attr("t");
        if (t != null) {
            try {
                dateEpochSec = Long.parseLong(t);
            } catch (NumberFormatException ignored) {
            }
        }
        boolean offline = node.attr("offline") != null;

        Long latencyMs = null;
        if (status == CallStatus.RELAYLATENCY) {
            String latencyValue = info.attr("latency");
            if (latencyValue == null) latencyValue = info.attr("latency_ms");
            if (latencyValue == null) latencyValue = info.attr("latency-ms");
            if (latencyValue != null) {
                try {
                    double latency = Double.parseDouble(latencyValue);
                    if (Double.isFinite(latency)) {
                        latencyMs = Math.round(latency);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        Boolean isVideo = null;
        Boolean isGroup = null;
        String groupJid = null;
        if (status == CallStatus.OFFER) {
            isVideo = info.child("video") != null;
            String groupJidAttr = info.attr("group-jid");
            isGroup = "group".equals(info.attr("type")) || groupJidAttr != null;
            groupJid = groupJidAttr;
        }

        CallEvent event = new CallEvent(
            node.attr("from"), from, callerPn, callId, dateEpochSec, offline,
            status, isVideo, isGroup, groupJid, latencyMs);

        if (status == CallStatus.OFFER) {
            offerCache.put(callId, event);
        }
        CallEvent existing = offerCache.get(callId);
        if (existing != null) {
            if (event.isVideo() == null) isVideo = existing.isVideo();
            if (event.isGroup() == null) isGroup = existing.isGroup();
            if (event.callerPn() == null) callerPn = existing.callerPn();
            if (!java.util.Objects.equals(isVideo, event.isVideo())
                    || !java.util.Objects.equals(isGroup, event.isGroup())
                    || !java.util.Objects.equals(callerPn, event.callerPn())) {
                event = new CallEvent(
                    event.chatId(), event.from(), callerPn, event.id(),
                    event.dateEpochSec(), event.offline(), event.status(),
                    isVideo, isGroup, event.groupJid(), event.latencyMs());
            }
        }
        if (status == CallStatus.REJECT || status == CallStatus.ACCEPT
                || status == CallStatus.TIMEOUT || status == CallStatus.TERMINATE) {
            offerCache.remove(callId);
        }
        return event;
    }

    /** @return number of offers currently cached (for diagnostics/tests). */
    public int cachedOfferCount() {
        return offerCache.size();
    }

    private static CallStatus statusFromNode(BinaryNode info) {
        String tag = info.tag();
        if (tag == null) return CallStatus.RINGING;
        return switch (tag) {
            case "offer", "offer_notice" -> CallStatus.OFFER;
            case "preaccept" -> CallStatus.PREACCEPT;
            case "transport" -> CallStatus.TRANSPORT;
            case "relaylatency" -> CallStatus.RELAYLATENCY;
            case "reject" -> CallStatus.REJECT;
            case "accept" -> CallStatus.ACCEPT;
            case "terminate" ->
                "timeout".equals(info.attr("reason")) ? CallStatus.TIMEOUT : CallStatus.TERMINATE;
            default -> CallStatus.RINGING;
        };
    }
}
