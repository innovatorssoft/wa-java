package id.ja_wa.feature.messaging;

import com.google.protobuf.ByteString;
import id.ja_wa.domain.model.Jid;
import id.ja_wa.proto.Wa;
import id.ja_wa.protocol.codec.BinaryNode;
import id.ja_wa.protocol.crypto.Bytes;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Message-building side of Baileys' status mentions
 * ({@code lib/Socket/messages-send.js}, {@code sendMessage} array-JID
 * branch, "Lia@Changes 13-03-26 — Add status mentions!").
 * <p>
 * When Baileys' {@code sendMessage} receives an <em>array</em> of JIDs
 * instead of one JID, it does not send a group message: it
 * <ol>
 *   <li>generates the content once addressed to {@code status@broadcast};</li>
 *   <li>expands the array into a flat participant list — every group
 *       JID is replaced by its participants (via cached or freshly
 *       fetched group metadata), every other JID is kept as-is,
 *       duplicates dropped — and relays the status message with that
 *       list as {@code statusJidList} (Baileys' relay fan-out for
 *       {@code status@broadcast} encrypts to those participants'
 *       devices);</li>
 *   <li>relays the same status message with an additional
 *       {@code <meta><mentioned_users><to jid="..."/>…</mentioned_users></meta>}
 *       node listing the <em>original</em> array JIDs (not the expanded
 *       list);</li>
 *   <li>for each original JID, relays a second, per-target message —
 *       {@code statusMentionMessage} for a user, or
 *       {@code groupStatusMentionMessage} for a group — whose inner
 *       protocol message points at the status message's key with type
 *       {@code 25} ({@code STATUS_MENTION_MESSAGE}), with an additional
 *       {@code <meta is_status_mention="true"/>} (user) or
 *       {@code <meta is_group_status_mention="true"/>} (group) node,
 *       waiting {@code delayMs} (default 1500 ms) between targets.</li>
 * </ol>
 * This class implements steps 1–4 as pure builders (the status content
 * itself is any {@link Wa.Message} the caller built, e.g. via
 * {@link MessageEncoder}; the status message's key is supplied by the
 * caller, since message ids are assigned by the client's send path).
 * It performs no encryption and no sending.
 * <p>
 * <b>Send-path wiring gap (parent hook):</b> wa-java's send path
 * ({@code JaWaClient.sendStatusText/sendStatusMedia} via
 * {@code sendDmMessage}, and {@code MessageSender.buildStanza}) has no
 * {@code statusJidList} fan-out, no {@code additionalNodes} support for
 * the {@code mentioned_users}/{@code is_*_status_mention} meta nodes,
 * and no per-target mention relay or inter-target delay. Wiring this
 * builder into a send therefore needs a new JaWaClient/MessagingService
 * method (which this increment must not add — JaWaClient is owned by
 * the parent) that: relays the content to {@code status@broadcast}
 * with the expanded list and the {@code mentioned_users} meta node,
 * then relays one mention message per original target with its meta
 * node, delaying 1500 ms between targets, and emits own-events if
 * configured. Nothing here changes send-path silent-failure behaviour:
 * group-metadata lookup failures are surfaced to the caller through
 * the resolver (a null/empty participant list skips that group, as
 * Baileys logs the error and continues) rather than swallowed here.
 */
public final class StatusMentionBuilder {

    /** The status broadcast JID Baileys addresses status content to. */
    public static final String STATUS_JID = "status@broadcast";

    /** Default inter-target delay in Baileys' status-mention loop, in milliseconds. */
    public static final long DEFAULT_DELAY_MS = 1500L;

    private StatusMentionBuilder() {}

    /** @return true when the JID is the status broadcast JID */
    public static boolean isStatusJid(String jid) {
        return STATUS_JID.equals(jid);
    }

    /** @return true when the JID is a group JID ({@code @g.us}) */
    public static boolean isGroupJid(String jid) {
        return jid != null && jid.endsWith("@" + Jid.SERVER_GROUP);
    }

    /**
     * Expands Baileys' array-JID input into the flat, deduplicated
     * participant list used as {@code statusJidList}: group JIDs are
     * replaced by their participants (looked up through
     * {@code groupParticipants}, which receives the group JID and
     * returns its participant JIDs, or null/empty when unknown), other
     * JIDs are kept as-is. Order is first-seen order; duplicates are
     * dropped, exactly as Baileys' {@code Set} accumulation does.
     * A group whose participants cannot be resolved contributes
     * nothing (Baileys logs the metadata error and continues) — the
     * group itself still appears in the {@code mentioned_users} node
     * and still gets its own mention message.
     *
     * @param targets original array-JID input; null yields empty
     * @param groupParticipants resolver from group JID to participant JIDs
     * @return expanded, deduplicated participant list
     */
    public static List<String> expandMentionTargets(List<String> targets,
            Function<String, List<String>> groupParticipants) {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        if (targets == null) return List.of();
        for (String id : targets) {
            if (id == null || id.isBlank()) continue;
            if (isGroupJid(id)) {
                List<String> participants = groupParticipants == null ? null : groupParticipants.apply(id);
                if (participants != null) {
                    for (String p : participants) {
                        if (p != null && !p.isBlank()) all.add(p);
                    }
                }
            } else if (!all.contains(id)) {
                all.add(id);
            }
        }
        return new ArrayList<>(all);
    }

    /**
     * Builds the {@code <meta><mentioned_users>} additional node for
     * the status relay, listing the <em>original</em> target JIDs
     * (Baileys maps the input array, not the expanded list).
     *
     * @param originalTargets original array-JID input
     * @return {@code meta} node, or a {@code meta} node with an empty
     *         {@code mentioned_users} child for null/empty input
     */
    public static BinaryNode mentionedUsersMetaNode(List<String> originalTargets) {
        List<BinaryNode> tos = new ArrayList<>();
        if (originalTargets != null) {
            for (String id : originalTargets) {
                if (id != null && !id.isBlank()) {
                    tos.add(new BinaryNode("to", Map.of("jid", id), null));
                }
            }
        }
        BinaryNode mentioned = new BinaryNode("mentioned_users", Map.of(), tos);
        return new BinaryNode("meta", Map.of(), List.of(mentioned));
    }

    /**
     * Builds the additional {@code <meta>} node for a per-target mention
     * relay: {@code is_group_status_mention="true"} for groups,
     * {@code is_status_mention="true"} otherwise.
     *
     * @param targetJid per-target JID
     * @return {@code meta} node
     */
    public static BinaryNode mentionMetaNode(String targetJid) {
        return new BinaryNode("meta",
            Map.of(isGroupJid(targetJid) ? "is_group_status_mention" : "is_status_mention", "true"),
            null);
    }

    /**
     * Builds a per-target status-mention message: a
     * {@code statusMentionMessage} (user target) or
     * {@code groupStatusMentionMessage} (group target) wrapping a
     * {@code FutureProofMessage} whose protocol message points at the
     * status message's key with type
     * {@code STATUS_MENTION_MESSAGE} (25), plus a fresh 32-byte message
     * secret in the message context info — matching Baileys'
     * {@code generateWAMessageFromContent} call in the mention loop.
     *
     * @param targetJid per-target JID (only its group-ness is used; the
     *                  message itself is not addressed here — addressing
     *                  is the send path's job)
     * @param statusKey key of the status message being mentioned; must not be null
     * @return mention message
     */
    public static Wa.Message statusMentionMessage(String targetJid, Wa.MessageKey statusKey) {
        if (statusKey == null) throw new IllegalArgumentException("statusKey must not be null");
        Wa.Message.ProtocolMessage protocol = Wa.Message.ProtocolMessage.newBuilder()
            .setKey(statusKey)
            .setType(Wa.Message.ProtocolMessage.Type.STATUS_MENTION_MESSAGE)
            .build();
        Wa.Message inner = Wa.Message.newBuilder().setProtocolMessage(protocol).build();
        Wa.Message.FutureProofMessage wrap = Wa.Message.FutureProofMessage.newBuilder()
            .setMessage(inner)
            .build();
        Wa.MessageContextInfo context = Wa.MessageContextInfo.newBuilder()
            .setMessageSecret(ByteString.copyFrom(Bytes.random(32)))
            .build();
        Wa.Message.Builder b = Wa.Message.newBuilder().setMessageContextInfo(context);
        if (isGroupJid(targetJid)) {
            b.setGroupStatusMentionMessage(wrap);
        } else {
            b.setStatusMentionMessage(wrap);
        }
        return b.build();
    }

    /**
     * Convenience bundle for one status-mention send: the expanded
     * {@code statusJidList}, the {@code mentioned_users} meta node for
     * the status relay, and one mention message plus its meta node per
     * original target.
     *
     * @param statusJidList expanded participant list (see {@link #expandMentionTargets})
     * @param mentionedUsersMeta {@code mentioned_users} meta node for the status relay
     * @param mentions per-target mention messages and meta nodes, keyed by target JID
     *                 in original-target order
     */
    public record StatusMentionPlan(List<String> statusJidList, BinaryNode mentionedUsersMeta,
                                    Map<String, Mention> mentions) {
        public StatusMentionPlan {
            statusJidList = statusJidList == null ? List.of() : List.copyOf(statusJidList);
            mentions = mentions == null ? Map.of() : Map.copyOf(mentions);
        }
    }

    /** One per-target mention: the message and its additional meta node. */
    public record Mention(Wa.Message message, BinaryNode metaNode) {}

    /**
     * Builds a complete plan for a status-mention send from the
     * original array-JID input and the status message's key.
     *
     * @param originalTargets original array-JID input
     * @param groupParticipants resolver from group JID to participant JIDs
     * @param statusKey key of the status message being mentioned; must not be null
     * @return plan ready for the send path to relay
     */
    public static StatusMentionPlan plan(List<String> originalTargets,
            Function<String, List<String>> groupParticipants, Wa.MessageKey statusKey) {
        if (statusKey == null) throw new IllegalArgumentException("statusKey must not be null");
        List<String> expanded = expandMentionTargets(originalTargets, groupParticipants);
        Map<String, Mention> mentions = new LinkedHashMap<>();
        if (originalTargets != null) {
            for (String id : originalTargets) {
                if (id == null || id.isBlank() || mentions.containsKey(id)) continue;
                mentions.put(id, new Mention(statusMentionMessage(id, statusKey), mentionMetaNode(id)));
            }
        }
        return new StatusMentionPlan(expanded, mentionedUsersMetaNode(originalTargets), mentions);
    }
}
