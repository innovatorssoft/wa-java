package id.ja_wa.feature.messaging;

import com.google.protobuf.InvalidProtocolBufferException;
import id.ja_wa.proto.Wa;
import id.ja_wa.protocol.codec.BinaryNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Pure builders for Baileys peer-data operations and the member-label
 * protocol message, plus the placeholder-resend cache.
 *
 * <p>Baileys sources (itsliaaa/baileys, HEAD 49b6687):
 * <ul>
 *   <li>{@code sendPeerDataOperationMessage} in
 *       {@code lib/Socket/messages-send.js}: relays a
 *       {@code protocolMessage} carrying
 *       {@code peerDataOperationRequestMessage} with type
 *       {@code PEER_DATA_OPERATION_REQUEST_MESSAGE} to the account's own
 *       JID ({@code jidNormalizedUser(me.id)}), with
 *       {@code additionalAttributes {category: 'peer', push_priority:
 *       'high_force'}} and
 *       {@code additionalNodes [<meta appdata='default'/>]}. It throws
 *       when not authenticated ({@code creds.me} absent).</li>
 *   <li>{@code requestPlaceholderResend} in
 *       {@code lib/Socket/messages-recv.js}: if the message id is already
 *       in the placeholder-resend cache, logs and returns without
 *       sending; otherwise stores the original message data (or
 *       {@code true} when none is given), waits 2 seconds, re-checks
 *       the cache (a hit removed meanwhile means the real message
 *       arrived, so it returns {@code 'RESOLVED'}), then sends a PDO
 *       request with type {@code PLACEHOLDER_MESSAGE_RESEND} holding one
 *       {@code placeholderMessageResendRequest} per message key, and
 *       schedules an 8-second timeout that deletes the cache entry
 *       (logging that the phone may be offline) if it is still
 *       present.</li>
 *   <li>{@code updateMemberLabel} in
 *       {@code lib/Socket/messages-send.js}: relays a
 *       {@code protocolMessage} with type
 *       {@code GROUP_MEMBER_LABEL_CHANGE} and a {@code memberLabel}
 *       of {@code {label: label.slice(0, 30), labelTimestamp:
 *       unixTimestampSeconds()}} to the caller-supplied JID, with
 *       {@code additionalNodes [<meta tag_reason='user_update'
 *       appdata='member_tag'/>]}.</li>
 *   <li>{@code fetchMessageHistory} in
 *       {@code lib/Socket/messages-recv.js} is the same PDO mechanism
 *       with type {@code HISTORY_SYNC_ON_DEMAND}; its builder is
 *       included here because it is blocked on the same PDO send.</li>
 * </ul>
 *
 * <p>Wire divergence, stated not hidden: Baileys' {@code relayMessage}
 * treats {@code category: 'peer'} specially — it skips USync device
 * enumeration and emits the peer stanza with a bare {@code <enc>} child
 * instead of a {@code <participants>} wrapper
 * ({@code lib/Socket/messages-send.js}, peer branches around the device
 * enumeration and the stanza build). Increment 8 wires the peer-mode
 * <em>live</em> send path in
 * {@link id.ja_wa.client.JaWaClient#sendPeerDataOperationMessage}:
 * it bypasses {@code queryDevices}, encrypts for the single own
 * device-0 JID ({@link MessageSender#buildPeerStanza}), reframes
 * with {@link MessageSender#peerFrame}, and sends with the peer
 * attributes and the {@code <meta>} node. The one remaining
 * fallback, stated in that method's Javadoc, is the case where the
 * client's own device is 0 (a bare {@code creds.meJid}): device 0
 * is then the sender itself, so the Increment 6 self-DM path
 * (enumerated own devices, {@code <participants>} framing) is kept
 * for that case.
 *
 * <p>This class performs no I/O. Sending is done by the caller through
 * {@link id.ja_wa.client.JaWaClient}.
 */
public final class PeerDataOperations {

    /** Baileys PDO stanza attribute: {@code category: 'peer'}. */
    public static final String PEER_CATEGORY = "peer";
    /** Baileys PDO stanza attribute: {@code push_priority: 'high_force'}. */
    public static final String PEER_PUSH_PRIORITY = "high_force";
    /** Baileys {@code requestPlaceholderResend} delay before re-checking the cache. */
    public static final long PLACEHOLDER_RESEND_DELAY_MS = 2000L;
    /** Baileys {@code requestPlaceholderResend} timeout after which a still-pending entry is dropped. */
    public static final long PLACEHOLDER_RESEND_TIMEOUT_MS = 8000L;
    /**
     * Increment 10: Baileys {@code PLACEHOLDER_MAX_AGE_SECONDS}
     * ({@code lib/Defaults/index.js:26}):
     * {@code 14 * 24 * 60 * 60} — "WA Web enforces a 14-day maximum
     * age for placeholder resend requests". Messages older than this
     * are skipped by
     * {@link #isPlaceholderResendEligible} and by Baileys'
     * {@code lib/Socket/messages-recv.js:1319} alike.
     */
    public static final long PLACEHOLDER_MAX_AGE_SECONDS = 14L * 24 * 60 * 60;
    /**
     * Increment 10: Baileys' {@code <unavailable>} type exclusions
     * ({@code lib/Socket/messages-recv.js:1311-1313}): messages whose
     * {@code <unavailable>} child carries one of these types are
     * skipped for placeholder resend (acked, no upsert, no claim).
     */
    public static final java.util.Set<String> EXCLUDED_UNAVAILABLE_TYPES = java.util.Set.of(
        "bot_unavailable_fanout", "hosted_unavailable_fanout", "view_once_unavailable_fanout");

    /**
     * Increment 10: pure placeholder-eligibility gate for a decrypt
     * failure, mirroring Baileys'
     * {@code lib/Socket/messages-recv.js:1301-1322} in order. A
     * failure is eligible only when ALL of these hold:
     * <ul>
     *   <li>its kind is {@code NO_MESSAGE_FOUND} (any other kind —
     *       MISSING_KEYS, libsignal failures, malformed stanzas — is
     *       ineligible, so existing behaviour for them is unchanged);</li>
     *   <li>the stanza is not {@code category: 'peer'} (Baileys'
     *       {@code msg.category !== 'peer'} gate at :1301);</li>
     *   <li>its {@code <unavailable>} type (null when absent) is not
     *       one of {@link #EXCLUDED_UNAVAILABLE_TYPES};</li>
     *   <li>it is not older than {@link #PLACEHOLDER_MAX_AGE_SECONDS}
     *       (age = {@code nowSec - messageTimestampSec}).</li>
     * </ul>
     * Timestamp edge, stated not hidden: when the timestamp is
     * missing or unparseable (0 or negative here, matching
     * {@code MessageReceiver.decode}'s default), the message is
     * treated as fresh, not skipped — Baileys computes
     * {@code unixTimestampSeconds() - toNumber(undefined)} = NaN, and
     * {@code NaN > PLACEHOLDER_MAX_AGE_SECONDS} is false, so Baileys
     * likewise does not skip a timestamp-less message on age grounds.
     *
     * @param kind failure kind, or null (treated as ineligible)
     * @param unavailableType the stanza's {@code <unavailable>} type attr, or null
     * @param category the stanza's {@code category} attr, or null
     * @param messageTimestampSec stanza {@code t} in unix seconds, or 0 when unknown
     * @param nowSec current unix seconds (injected so tests never sleep)
     * @return true only when a placeholder upsert + claim should happen
     */
    public static boolean isPlaceholderResendEligible(
            MessageReceiver.DecryptFailureKind kind, String unavailableType,
            String category, long messageTimestampSec, long nowSec) {
        if (kind == null || !kind.isPlaceholderEligible()) return false;
        if ("peer".equals(category)) return false;
        if (unavailableType != null && EXCLUDED_UNAVAILABLE_TYPES.contains(unavailableType)) {
            return false;
        }
        if (messageTimestampSec > 0 && nowSec - messageTimestampSec > PLACEHOLDER_MAX_AGE_SECONDS) {
            return false;
        }
        return true;
    }
    /** Baileys {@code updateMemberLabel} label truncation: {@code label.slice(0, 30)}. */
    public static final int MEMBER_LABEL_MAX_LENGTH = 30;

    private PeerDataOperations() {}

    /** Baileys PDO {@code <meta appdata='default'/>} additional node. */
    public static BinaryNode peerMetaNode() {
        return new BinaryNode("meta", Map.of("appdata", "default"), null);
    }

    /** Baileys member-label {@code <meta tag_reason='user_update' appdata='member_tag'/>} node. */
    public static BinaryNode memberLabelMetaNode() {
        return new BinaryNode("meta",
            Map.of("tag_reason", "user_update", "appdata", "member_tag"), null);
    }

    /** Baileys PDO stanza attributes: {@code {category: 'peer', push_priority: 'high_force'}}. */
    public static Map<String, String> peerAttributes() {
        return Map.of("category", PEER_CATEGORY, "push_priority", PEER_PUSH_PRIORITY);
    }

    /**
     * Wraps a caller-built PDO request in Baileys' PDO protocol message.
     * The caller owns the request type and its payload fields; this
     * method only applies the fixed envelope (type
     * {@code PEER_DATA_OPERATION_REQUEST_MESSAGE}).
     */
    public static Wa.Message buildPeerDataOperationMessage(
            Wa.Message.PeerDataOperationRequestMessage pdo) {
        Objects.requireNonNull(pdo, "pdo");
        Wa.Message.ProtocolMessage protocol = Wa.Message.ProtocolMessage.newBuilder()
            .setType(Wa.Message.ProtocolMessage.Type.PEER_DATA_OPERATION_REQUEST_MESSAGE)
            .setPeerDataOperationRequestMessage(pdo)
            .build();
        return Wa.Message.newBuilder().setProtocolMessage(protocol).build();
    }

    /**
     * Baileys {@code fetchMessageHistory} PDO payload: a
     * {@code HISTORY_SYNC_ON_DEMAND} request for {@code count} messages
     * older than the given key. {@code accountLid} is not set: Baileys'
     * compiled {@code fetchMessageHistory} does not set it.
     */
    public static Wa.Message.PeerDataOperationRequestMessage buildHistorySyncOnDemandRequest(
            String chatJid, String oldestMsgId, boolean oldestMsgFromMe,
            int onDemandMsgCount, long oldestMsgTimestampMs) {
        Objects.requireNonNull(chatJid, "chatJid");
        Objects.requireNonNull(oldestMsgId, "oldestMsgId");
        Wa.Message.PeerDataOperationRequestMessage.HistorySyncOnDemandRequest history =
            Wa.Message.PeerDataOperationRequestMessage.HistorySyncOnDemandRequest.newBuilder()
                .setChatJid(chatJid)
                .setOldestMsgId(oldestMsgId)
                .setOldestMsgFromMe(oldestMsgFromMe)
                .setOnDemandMsgCount(onDemandMsgCount)
                .setOldestMsgTimestampMs(oldestMsgTimestampMs)
                .build();
        return Wa.Message.PeerDataOperationRequestMessage.newBuilder()
            .setPeerDataOperationRequestType(
                Wa.Message.PeerDataOperationRequestType.HISTORY_SYNC_ON_DEMAND)
            .setHistorySyncOnDemandRequest(history)
            .build();
    }

    /**
     * Baileys {@code requestPlaceholderResend} PDO payload: a
     * {@code PLACEHOLDER_MESSAGE_RESEND} request holding one
     * {@code placeholderMessageResendRequest} for the given key.
     */
    public static Wa.Message.PeerDataOperationRequestMessage buildPlaceholderResendRequest(
            Wa.MessageKey messageKey) {
        Objects.requireNonNull(messageKey, "messageKey");
        Wa.Message.PeerDataOperationRequestMessage.PlaceholderMessageResendRequest req =
            Wa.Message.PeerDataOperationRequestMessage.PlaceholderMessageResendRequest.newBuilder()
                .setMessageKey(messageKey)
                .build();
        return Wa.Message.PeerDataOperationRequestMessage.newBuilder()
            .setPeerDataOperationRequestType(
                Wa.Message.PeerDataOperationRequestType.PLACEHOLDER_MESSAGE_RESEND)
            .addPlaceholderMessageResendRequest(req)
            .build();
    }

    /**
     * One consumed placeholder-resend response: the stored original
     * message data the resend was requested for (whatever the caller
     * passed to {@code requestPlaceholderResend} — a
     * {@code MessageReceiver.Decoded}, or {@code Boolean.TRUE} when
     * none was given, per {@link PlaceholderResendManager#tryClaim}),
     * plus the phone's decoded {@code WebMessageInfo} reply.
     *
     * @param storedData data stored under the message id at request
     *                   time, or null when nothing was claimed for it
     * @param info decoded {@code WebMessageInfo} from the response
     */
    public record ResolvedPlaceholder(Object storedData, Wa.WebMessageInfo info) {}

    /**
     * Increment 7 (stretch): Baileys' inbound PDO-response handling
     * for placeholder resends
     * ({@code lib/Utils/process-message.js:385},
     * {@code PEER_DATA_OPERATION_REQUEST_RESPONSE_MESSAGE} case).
     * For each result carrying a
     * {@code placeholderMessageResendResponse} with
     * {@code webMessageInfoBytes}, decodes the
     * {@code WebMessageInfo}, reads the stored original data for its
     * key id from {@code manager}, and resolves (deletes) the claim
     * — Baileys' {@code placeholderResendCache.del(msgId)} — so a
     * still-pending resend request observes the arrival as
     * {@code RESOLVED} instead of sending. Results without
     * {@code webMessageInfoBytes}, and bytes that fail to decode,
     * are skipped (Baileys logs and continues per result). Other
     * result kinds (history-sync, sticker, link-preview, ...) are
     * ignored here exactly as Baileys ignores them: its own code
     * carries a {@code TODO: IMPLEMENT HISTORY SYNC ETC} above this
     * loop. The caller (JaWaClient's receiver branch) turns each
     * {@link ResolvedPlaceholder} into a {@code messages.upsert}
     * payload, applying the decoded message onto stored
     * {@code Decoded} metadata when the stored data is one, as
     * Baileys applies it onto cached object data.
     *
     * @param response inbound PDO response message, or null
     * @param manager placeholder-resend manager, or null (then no
     *                stored data is read and nothing is resolved,
     *                but responses are still decoded and returned)
     * @return resolved responses in result order, never null
     */
    public static List<ResolvedPlaceholder> resolvePlaceholderResendResponses(
            Wa.Message.PeerDataOperationRequestResponseMessage response,
            PlaceholderResendManager manager) {
        List<ResolvedPlaceholder> out = new ArrayList<>();
        if (response == null) return out;
        for (Wa.Message.PeerDataOperationRequestResponseMessage.PeerDataOperationResult result
                : response.getPeerDataOperationResultList()) {
            if (!result.hasPlaceholderMessageResendResponse()) continue;
            com.google.protobuf.ByteString bytes =
                result.getPlaceholderMessageResendResponse().getWebMessageInfoBytes();
            if (bytes == null || bytes.isEmpty()) continue;
            Wa.WebMessageInfo info;
            try {
                info = Wa.WebMessageInfo.parseFrom(bytes);
            } catch (InvalidProtocolBufferException ignored) {
                continue;
            }
            String msgId = info.hasKey() ? info.getKey().getId() : null;
            Object stored = null;
            if (manager != null && msgId != null && !msgId.isEmpty()) {
                stored = manager.stored(msgId);
                manager.resolve(msgId);
            }
            out.add(new ResolvedPlaceholder(stored, info));
        }
        return out;
    }

    /**
     * Baileys {@code updateMemberLabel} protocol message. The label is
     * truncated exactly as Baileys truncates it:
     * {@code label.slice(0, 30)} — Java {@link String} is UTF-16, so
     * {@code substring(0, 30)} matches JS {@code slice} code-unit
     * semantics, including splitting a surrogate pair at the boundary
     * (Baileys does the same). A null label leaves the field unset,
     * mirroring Baileys' {@code memberLabel?.slice(0, 30)} yielding
     * {@code undefined}; callers normally pass a non-null label and the
     * current unix timestamp in seconds.
     */
    public static Wa.Message buildMemberLabelMessage(String label, long labelTimestampSeconds) {
        Wa.MemberLabel.Builder memberLabel = Wa.MemberLabel.newBuilder()
            .setLabelTimestamp(labelTimestampSeconds);
        if (label != null) {
            memberLabel.setLabel(truncateLabel(label));
        }
        Wa.Message.ProtocolMessage protocol = Wa.Message.ProtocolMessage.newBuilder()
            .setType(Wa.Message.ProtocolMessage.Type.GROUP_MEMBER_LABEL_CHANGE)
            .setMemberLabel(memberLabel.build())
            .build();
        return Wa.Message.newBuilder().setProtocolMessage(protocol).build();
    }

    /** Baileys {@code label.slice(0, 30)}; null stays null. */
    public static String truncateLabel(String label) {
        if (label == null) return null;
        return label.length() > MEMBER_LABEL_MAX_LENGTH
            ? label.substring(0, MEMBER_LABEL_MAX_LENGTH)
            : label;
    }

    /**
     * The placeholder-resend cache from Baileys
     * {@code lib/Socket/chats.js} ({@code placeholderResendCache}, TTL
     * one hour via {@code DEFAULT_CACHE_TTLS.MSG_RETRY}) and
     * {@code requestPlaceholderResend} in
     * {@code lib/Socket/messages-recv.js}, as a testable state machine.
     *
     * <p>Baileys' cache is a {@code createCache} keyed by message id
     * holding the original message data (or {@code true}) so the PDO
     * response handler can preserve metadata (LID details, timestamps)
     * the phone may omit. This manager keeps the same mapping and the
     * same claim / re-check / expire transitions; it does not itself
     * expire entries on a timer — the caller schedules the 8-second
     * expiry (see {@link #expire}) and the 2-second delay is injected
     * through {@link Delay}, so tests never sleep.
     *
     * <p>Increment 7: the receiver hooks now exist. JaWaClient's
     * inbound-message path calls {@link #resolve} when a decoded
     * message's id is pending (real-message arrival), and its
     * PDO-response branch consumes stored data via
     * {@link PeerDataOperations#resolvePlaceholderResendResponses}
     * and dispatches the resolved messages as
     * {@code messages.upsert}. MessageReceiver itself still has no
     * placeholder handling, and the existing retry-receipt path
     * ({@code sendMediaRetryReceipt}) is a different mechanism
     * (media retry, not placeholder resend); wa-java also still has
     * no ciphertext-stub placeholder upsert, so
     * {@code requestPlaceholderResend} was manual-only, never
     * auto-triggered by an undecryptable message. Increment 10
     * narrowed that statement: JaWaClient's decrypt-failure catch
     * upserts a CIPHERTEXT stub and claims the id for a
     * NO_MESSAGE_FOUND failure only (see
     * {@link id.ja_wa.client.JaWaClient#handleDecryptFailurePlaceholder}).
     * Increment 10b removes the remainder: the catch's helper now
     * also invokes {@code requestPlaceholderResend} itself (claim +
     * delay + PDO send), so a subsequent manual
     * {@code requestPlaceholderResend} observes the claim
     * (already-requested, null) rather than sending a duplicate.
     */
    public static final class PlaceholderResendManager {

        /** Injectable delay: {@code after(ms)} completes after that many milliseconds. */
        @FunctionalInterface
        public interface Delay {
            CompletableFuture<Void> after(long millis);

            /** Production delay, via {@link CompletableFuture#delayedExecutor}. No thread sleeps. */
            static Delay system() {
                return millis -> CompletableFuture.runAsync(() -> {},
                    CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS));
            }
        }

        private final ConcurrentHashMap<String, Object> cache = new ConcurrentHashMap<>();

        /**
         * Claims the message id, storing {@code msgData} (or
         * {@code Boolean.TRUE} when null, as Baileys stores
         * {@code msgData || true}). Returns false when the id is
         * already claimed — Baileys' "already requested resend" path,
         * which sends nothing.
         */
        public boolean tryClaim(String messageId, Object msgData) {
            Objects.requireNonNull(messageId, "messageId");
            return cache.putIfAbsent(messageId, msgData == null ? Boolean.TRUE : msgData) == null;
        }

        /** Whether the id is still claimed (not resolved or expired). */
        public boolean isPending(String messageId) {
            return messageId != null && cache.containsKey(messageId);
        }

        /** Stored original message data for the id, or null when not claimed. */
        public Object stored(String messageId) {
            return messageId == null ? null : cache.get(messageId);
        }

        /** Marks the real message as arrived: drops the claim so a pending resend aborts. */
        public void resolve(String messageId) {
            if (messageId != null) cache.remove(messageId);
        }

        /**
         * Baileys' 8-second timeout: drops the claim when it is still
         * present (phone possibly offline). Returns true when an entry
         * was actually dropped.
         */
        public boolean expire(String messageId) {
            return messageId != null && cache.remove(messageId) != null;
        }

        /**
         * Full Baileys {@code requestPlaceholderResend} flow for one
         * message id, with the delay injected.
         *
         * @param messageId message id (the cache key, Baileys uses {@code messageKey.id})
         * @param msgData original message data, or null to store {@code true}
         * @param delay injected 2-second delay (production: {@link Delay#system()})
         * @param sender invoked only when the claim survives the delay;
         *               builds and sends the PDO request. The caller
         *               schedules the 8-second {@link #expire} when the
         *               sender runs, mirroring Baileys' {@code setTimeout}
         *               placed just before {@code sendPeerDataOperationMessage}.
         * @return future of null (already requested), {@code "RESOLVED"}
         *         (claim dropped during the delay), or the sender's
         *         result (the sent message id)
         */
        public CompletableFuture<String> request(String messageId, Object msgData,
                Delay delay, Supplier<CompletableFuture<String>> sender) {
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(delay, "delay");
            Objects.requireNonNull(sender, "sender");
            if (!tryClaim(messageId, msgData)) {
                return CompletableFuture.completedFuture(null);
            }
            return delay.after(PLACEHOLDER_RESEND_DELAY_MS).thenCompose(ignored -> {
                if (!isPending(messageId)) {
                    return CompletableFuture.completedFuture("RESOLVED");
                }
                return sender.get();
            });
        }
    }
}
