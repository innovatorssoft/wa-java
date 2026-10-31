package id.ja_wa.feature.messaging;

import com.google.protobuf.ByteString;
import id.ja_wa.event.LidMappingUpdate;
import id.ja_wa.event.MessagingHistorySet;
import id.ja_wa.feature.media.MediaCrypto;
import id.ja_wa.feature.media.MediaDownloader;
import id.ja_wa.proto.Wa;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.InflaterInputStream;

/**
 * History-sync notification handling, mirroring Baileys
 * {@code lib/Utils/history.js} ({@code downloadAndProcessHistorySyncNotification},
 * {@code processHistoryMessage}, {@code getHistoryMsg}) and the gates in
 * {@code lib/Socket/chats.js} / {@code lib/Utils/process-message.js}.
 *
 * <p>What is implemented here:
 * <ul>
 *   <li>{@link #extractNotification(Wa.Message)}: Baileys'
 *       {@code getHistoryMsg} — the protocol message's
 *       {@code historySyncNotification}, unwrapping ephemeral /
 *       view-once containers the way Baileys' message normalisation
 *       does (up to 4 wrapper levels).</li>
 *   <li>{@link #shouldProcessHistoryMessage(Wa.Message.HistorySyncType)}:
 *       Baileys' processing gate — the type must be in
 *       {@code PROCESSABLE_HISTORY_TYPES} ({@code lib/Defaults/index.js})
 *       and, under Baileys' default {@code shouldSyncHistoryMessage},
 *       must not be {@code FULL}.</li>
 *   <li>{@link #decodeInline(Wa.Message.HistorySyncNotification)}:
 *       the inline-payload branch of
 *       {@code downloadAndProcessHistorySyncNotification} —
 *       {@code HistorySync.decode(inflate(initialHistBootstrapInlinePayload))}.
 *       Java's {@link InflaterInputStream} is the zlib inflate Baileys'
 *       {@code node:zlib} inflate performs (same precedent as
 *       {@code BinaryDecoder}).</li>
 *   <li>{@link #toHistorySet(Wa.HistorySync, Wa.Message.HistorySyncNotification, Boolean)}:
 *       the {@code messaging-history.set} payload shape from
 *       {@code process-message.js}: chats and contacts as JID lists
 *       (wa-java has no chat/contact store — see
 *       {@link MessagingHistorySet}), messages mapped from
 *       {@code HistorySyncMsg} web messages to
 *       {@link MessageReceiver.Decoded}, {@code chunkOrder} and
 *       {@code peerDataRequestSessionId} from the notification, and
 *       {@code isLatest} supplied by the caller (Baileys: undefined for
 *       {@code ON_DEMAND}, otherwise "no previously processed history
 *       messages").</li>
 *   <li>{@link #lidPnMappings(Wa.HistorySync)}: the LID-PN mappings
 *       {@code processHistoryMessage} extracts — explicit
 *       {@code phoneNumberToLidMappings}, conversation
 *       {@code lidJid}/{@code pnJid} pairs, and the user-receipt
 *       fallback for LID chats without a {@code pnJid}.</li>
 * </ul>
 *
 * <p>Non-inline branch (Increment 5): Baileys'
 * {@code downloadAndProcessHistorySyncNotification} falls back to
 * {@code downloadHistory} — {@code downloadContentFromMessage(msg,
 * 'md-msg-hist')} then zlib inflate then {@code HistorySync.decode}
 * ({@code lib/Utils/history.js}). That branch is implemented here as
 * {@link #downloadHistory(Wa.Message.HistorySyncNotification)}: the
 * notification carries no {@code url} field, so Baileys resolves the
 * download URL from {@code directPath} alone
 * ({@code lib/Utils/messages-media.js}: {@code directPath ?
 * getUrlFromDirectPath(directPath) : url}, default host
 * {@code mmg.whatsapp.net}), and decrypts with HKDF info
 * {@code WhatsApp History Keys}
 * ({@link MediaCrypto.MediaType#HISTORY}). One caveat survives: the
 * decrypt step reuses wa-java's standard media format
 * (ciphertext + 10-byte MAC, as for image/video/audio/document
 * downloads), which matches the shared MMS serving path
 * ({@code /mms/md-app-state} in Baileys' {@code MEDIA_PATH_MAP}) but
 * has not been confirmed against a live history file; a download that
 * fails the MAC check yields null here, never a partial sync.
 * <p>What is deliberately NOT implemented (documented, not faked):
 * the business-contact entries Baileys adds from biz-privacy stub
 * messages are not carried (wa-java's {@link MessagingHistorySet}
 * carries contact JIDs only), and per-message text is not re-derived
 * for history messages ({@link MessageReceiver.Decoded#text()} is
 * null for them).
 *
 * <p>All methods are null-safe and never throw for malformed input:
 * a bad payload yields null / empty results, matching Baileys'
 * log-and-continue behaviour at these sites.
 */
public final class HistorySyncNotificationParser {

    /** Baileys {@code PROCESSABLE_HISTORY_TYPES} (lib/Defaults/index.js). */
    public static final Set<Wa.Message.HistorySyncType> PROCESSABLE_HISTORY_TYPES = Set.of(
        Wa.Message.HistorySyncType.INITIAL_BOOTSTRAP,
        Wa.Message.HistorySyncType.PUSH_NAME,
        Wa.Message.HistorySyncType.RECENT,
        Wa.Message.HistorySyncType.FULL,
        Wa.Message.HistorySyncType.ON_DEMAND,
        Wa.Message.HistorySyncType.NON_BLOCKING_DATA,
        Wa.Message.HistorySyncType.INITIAL_STATUS_V3
    );

    private HistorySyncNotificationParser() {}

    /**
     * Extracts the history-sync notification from a decoded message,
     * unwrapping ephemeral / view-once containers first.
     *
     * @param message decoded message; null yields null
     * @return the notification, or null when the message carries none
     */
    public static Wa.Message.HistorySyncNotification extractNotification(Wa.Message message) {
        Wa.Message current = message;
        for (int depth = 0; depth < 4 && current != null; depth++) {
            if (current.hasProtocolMessage()) {
                Wa.Message.ProtocolMessage protocol = current.getProtocolMessage();
                if (protocol.hasHistorySyncNotification()) {
                    return protocol.getHistorySyncNotification();
                }
                return null;
            }
            Wa.Message next = null;
            if (current.hasEphemeralMessage() && current.getEphemeralMessage().hasMessage()) {
                next = current.getEphemeralMessage().getMessage();
            } else if (current.hasViewOnceMessage() && current.getViewOnceMessage().hasMessage()) {
                next = current.getViewOnceMessage().getMessage();
            } else if (current.hasViewOnceMessageV2() && current.getViewOnceMessageV2().hasMessage()) {
                next = current.getViewOnceMessageV2().getMessage();
            }
            if (next == null) {
                return null;
            }
            current = next;
        }
        return null;
    }

    /**
     * Baileys' processing gate: processable type and not {@code FULL}
     * (Baileys' default {@code shouldSyncHistoryMessage}).
     *
     * @param syncType notification sync type; null is not processable
     * @return true when the notification should be processed
     */
    public static boolean shouldProcessHistoryMessage(Wa.Message.HistorySyncType syncType) {
        return syncType != null
            && PROCESSABLE_HISTORY_TYPES.contains(syncType)
            && syncType != Wa.Message.HistorySyncType.FULL;
    }

    /**
     * Decodes the inline bootstrap payload, if present.
     *
     * @param notification history-sync notification; null yields null
     * @return decoded {@code HistorySync}, or null when there is no
     *         inline payload, it is empty, it fails to inflate, or it
     *         fails to parse
     */
    public static Wa.HistorySync decodeInline(
            Wa.Message.HistorySyncNotification notification) {
        if (notification == null) {
            return null;
        }
        ByteString payload = notification.getInitialHistBootstrapInlinePayload();
        if (payload == null || payload.isEmpty()) {
            return null;
        }
        return decodeDownloaded(payload.toByteArray());
    }

    /**
     * Inflates a zlib-compressed history payload and parses it as a
     * {@code HistorySync}. This is the shared tail of both Baileys
     * branches: the inline payload is already deflated bytes, and the
     * downloaded history file is deflated bytes after decryption
     * (Baileys pipes the decrypted stream through zlib inflate in
     * {@code downloadHistory}).
     *
     * @param deflated zlib-compressed history bytes; null or empty yields null
     * @return decoded {@code HistorySync}, or null when the input fails
     *         to inflate or parse
     */
    public static Wa.HistorySync decodeDownloaded(byte[] deflated) {
        if (deflated == null || deflated.length == 0) {
            return null;
        }
        try {
            byte[] inflated = inflate(deflated);
            return Wa.HistorySync.parseFrom(inflated);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Baileys' {@code downloadHistory} ({@code lib/Utils/history.js}):
     * downloads the history file referenced by a payload-less
     * notification, decrypts it as {@code md-msg-hist} media and
     * inflates + decodes it. The notification has no {@code url}, so
     * the download URL is built from {@code directPath} on the
     * default media host, exactly as Baileys'
     * {@code downloadContentFromMessage} does.
     *
     * @param notification history-sync notification; null, a blank
     *         {@code directPath}, or an empty {@code mediaKey} yields null
     * @return decoded {@code HistorySync}, or null when the download,
     *         decrypt, inflate, or parse fails (null-safe, matching
     *         the rest of this parser: callers log and continue)
     */
    public static Wa.HistorySync downloadHistory(
            Wa.Message.HistorySyncNotification notification) {
        if (notification == null) {
            return null;
        }
        String directPath = notification.getDirectPath();
        byte[] mediaKey = notification.getMediaKey() == null
            ? null : notification.getMediaKey().toByteArray();
        if (directPath == null || directPath.isBlank()
                || mediaKey == null || mediaKey.length == 0) {
            return null;
        }
        String url = "https://mmg.whatsapp.net"
            + (directPath.startsWith("/") ? directPath : "/" + directPath);
        byte[] decrypted = MediaDownloader.fetchAndDecrypt(url, mediaKey,
            MediaCrypto.MediaType.HISTORY);
        if (decrypted == null) {
            return null;
        }
        return decodeDownloaded(decrypted);
    }

    /**
     * Builds the {@code messaging-history.set} payload from a decoded
     * history sync and its notification.
     *
     * @param sync decoded history sync; null yields null
     * @param notification the notification carrying the sync; may be null
     * @param isLatest Baileys {@code isLatest} (null for ON_DEMAND)
     * @return history-set payload, or null when {@code sync} is null
     */
    public static MessagingHistorySet toHistorySet(Wa.HistorySync sync,
            Wa.Message.HistorySyncNotification notification,
            Boolean isLatest) {
        if (sync == null) {
            return null;
        }
        LinkedHashSet<String> chatIds = new LinkedHashSet<>();
        LinkedHashSet<String> contactIds = new LinkedHashSet<>();
        List<MessageReceiver.Decoded> messages = new ArrayList<>();
        for (Wa.Conversation conversation : sync.getConversationsList()) {
            String id = conversation.getId();
            if (id != null && !id.isEmpty()) {
                chatIds.add(id);
                contactIds.add(id);
            }
            for (Wa.HistorySyncMsg item : conversation.getMessagesList()) {
                MessageReceiver.Decoded decoded = toDecoded(item.getMessage());
                if (decoded != null) {
                    messages.add(decoded);
                }
            }
        }
        for (Wa.Pushname pushname : sync.getPushnamesList()) {
            if (pushname.getId() != null && !pushname.getId().isEmpty()) {
                contactIds.add(pushname.getId());
            }
        }
        String syncType = sync.hasSyncType()
            ? sync.getSyncType().name()
            : (notification != null ? notification.getSyncType().name() : null);
        Long progress = sync.hasProgress() ? (long) sync.getProgress() : null;
        Integer chunkOrder = notification != null && notification.hasChunkOrder()
            ? notification.getChunkOrder() : null;
        String peerDataRequestSessionId = notification != null
            && !notification.getPeerDataRequestSessionId().isEmpty()
            ? notification.getPeerDataRequestSessionId() : null;
        return new MessagingHistorySet(
            new ArrayList<>(chatIds), messages, new ArrayList<>(contactIds),
            syncType, progress, isLatest, chunkOrder, peerDataRequestSessionId);
    }

    /**
     * Extracts LID-PN mappings from a decoded history sync, in Baileys'
     * order: explicit mappings first, then per-conversation pairs
     * (including the user-receipt fallback for LID chats without a
     * {@code pnJid}).
     *
     * @param sync decoded history sync; null yields an empty list
     * @return mappings, possibly with duplicates (as in Baileys)
     */
    public static List<LidMappingUpdate> lidPnMappings(Wa.HistorySync sync) {
        List<LidMappingUpdate> out = new ArrayList<>();
        if (sync == null) {
            return out;
        }
        for (Wa.PhoneNumberToLIDMapping mapping : sync.getPhoneNumberToLidMappingsList()) {
            if (!mapping.getLidJid().isEmpty() && !mapping.getPnJid().isEmpty()) {
                out.add(new LidMappingUpdate(mapping.getLidJid(), mapping.getPnJid()));
            }
        }
        for (Wa.Conversation conversation : sync.getConversationsList()) {
            String id = conversation.getId();
            if (id == null || id.isEmpty()) {
                continue;
            }
            String pnJid = conversation.getPnJid();
            String lidJid = conversation.getLidJid();
            if (isLid(id) && !pnJid.isEmpty()) {
                out.add(new LidMappingUpdate(id, pnJid));
            } else if (isPn(id) && !lidJid.isEmpty()) {
                out.add(new LidMappingUpdate(lidJid, id));
            } else if (isLid(id) && pnJid.isEmpty()) {
                String pnFromReceipt = extractPnFromMessages(conversation);
                if (pnFromReceipt != null) {
                    out.add(new LidMappingUpdate(id, pnFromReceipt));
                }
            }
        }
        return out;
    }

    private static MessageReceiver.Decoded toDecoded(Wa.WebMessageInfo info) {
        if (info == null || !info.hasMessage()) {
            return null;
        }
        Wa.MessageKey key = info.getKey();
        if (key == null || key.getRemoteJid().isEmpty()) {
            return null;
        }
        String remoteJid = key.getRemoteJid();
        String senderJid = !key.getParticipant().isEmpty() ? key.getParticipant() : remoteJid;
        String groupJid = remoteJid.endsWith("@g.us") ? remoteJid : null;
        return new MessageReceiver.Decoded(senderJid, groupJid, key.getId(), null,
            info.getMessage(), null, null, info.getMessageTimestamp());
    }

    private static String extractPnFromMessages(Wa.Conversation conversation) {
        for (Wa.HistorySyncMsg item : conversation.getMessagesList()) {
            Wa.WebMessageInfo info = item.getMessage();
            if (info == null || !info.hasKey() || !info.getKey().getFromMe()) {
                continue;
            }
            if (info.getUserReceiptCount() > 0) {
                String userJid = info.getUserReceipt(0).getUserJid();
                if (userJid != null && isPn(userJid)) {
                    return userJid;
                }
            }
        }
        return null;
    }

    private static boolean isLid(String jid) {
        return jid != null && jid.endsWith("@lid");
    }

    private static boolean isPn(String jid) {
        return jid != null && jid.endsWith("@s.whatsapp.net");
    }

    private static byte[] inflate(byte[] compressed) throws IOException {
        try (InflaterInputStream in = new InflaterInputStream(new ByteArrayInputStream(compressed));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
